package com.whiteeyes.admin.event.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.notification.service.NotificationPublisher;
import com.whiteeyes.admin.stat.service.LastResultTimeStore;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.service.WatchedCameraService;
import com.whiteeyes.common.camera.service.WatchedCameraService.WatchedCamera;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;

/**
 * 캠 이상 감지(FNC-A-035). 가동 중이어야 하는 캠이 오류이거나 결과가 끊기면 이벤트를 열고,
 * 복구·정지되면 닫는다. 10초마다 전체를 훑는 {@link #detect}와 상태 보고·정지 때 바로 처리하는 메서드로 나뉜다.
 * 새 이벤트를 열면 관리자 화면 알림(FNC-A-048)으로 알린다.
 */
@Slf4j
@Service
public class CamFaultEventService {

    // 필드: 감시 대상 캠 조회, 이벤트 저장소, 캠별 마지막 결과 시각 저장소, 화면 알림 발행기
    private final WatchedCameraService watchedCameraService;
    private final EventRepository eventRepository;
    private final LastResultTimeStore lastResultTimeStore;
    private final NotificationPublisher notificationPublisher;
    // timeoutSec은 이벤트 스냅샷 JSON에 남기는 값, timeout은 시간 비교에 쓰는 같은 값의 Duration 형태
    private final long timeoutSec;
    private final Duration timeout;

    public CamFaultEventService(
            WatchedCameraService watchedCameraService,
            EventRepository eventRepository,
            LastResultTimeStore lastResultTimeStore,
            NotificationPublisher notificationPublisher,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
            @Value("${CAM_FAULT_TIMEOUT_SEC:60}") long timeoutSec) {
        this.watchedCameraService = watchedCameraService;
        this.eventRepository = eventRepository;
        this.lastResultTimeStore = lastResultTimeStore;
        this.notificationPublisher = notificationPublisher;
        this.timeoutSec = timeoutSec;
        this.timeout = Duration.ofSeconds(timeoutSec);
    }

    /** 감시 대상 캠을 모두 검사해 이벤트를 열고, 조건이 풀렸거나 대상에서 빠진 캠의 열린 이벤트를 닫는다. */
    @Transactional
    public void detect(LocalDateTime now) {
        // 1) 지금 감시해야 하는 캠(가동 요청된 캠) 목록을 가져온다
        List<WatchedCamera> watched = watchedCameraService.findWatchedCameras();
        // 이번 검사에서 이상으로 판정된 캠 번호 모음. 뒤에서 '열린 이벤트를 닫을지'를 가르는 기준이 된다
        Set<Long> faultyCameraIds = new HashSet<>();
        // 기준 시각이 감시 시작 시각뿐인 캠(재시작 직후 등): 결과를 한 번도 못 받아 정상인지 알 수 없다
        Set<Long> unknownCameraIds = new HashSet<>();
        // 2) 캠마다 이상 여부를 판정하고, 이상이면 열린 이벤트가 없을 때만 새로 연다
        for (WatchedCamera camera : watched) {
            if (isFaulty(camera, now, unknownCameraIds)) {
                faultyCameraIds.add(camera.cameraId());
                openIfAbsent(camera.cameraId(), camera.zoneId(), now);
            }
        }
        // 조건이 풀린 캠과 정지·삭제로 대상에서 빠진 캠은 faultyCameraIds에 없으므로 한 번에 닫힌다.
        // 단 정상인지 알 수 없는 캠(unknownCameraIds)의 열린 이벤트는 닫지 않고 둔다(결과가 오면 다음 검사에서 닫힌다)
        // 3) 열린 캠 이상 이벤트를 훑어, 이번에 이상으로 판정되지 않은 캠의 이벤트를 닫는다
        int closed = 0;
        for (Event event : eventRepository.findByTypeAndEndedAtIsNull(EventType.CAM_FAULT)) {
            if (!faultyCameraIds.contains(event.getCameraId()) && !unknownCameraIds.contains(event.getCameraId())) {
                closeEvent(event, now);
                closed++;
            }
        }
        // 검사 결과 요약(운영 중 이상 감지가 도는지 확인하는 용도)
        log.debug("캠 이상 검사 대상={} 이상={} 닫음={}", watched.size(), faultyCameraIds.size(), closed);
    }

    /** 상태 보고가 ERROR일 때 부른다. run_requested=1인 캠만 이벤트를 연다(FNC-A-025 처리 순서 3). */
    @Transactional
    public void openOnError(Long cameraId, LocalDateTime now) {
        // 감시 대상이 아닌 캠(run_requested=0 등)이면 비어 있어 아무것도 하지 않는다
        watchedCameraService
                .findWatchedCamera(cameraId)
                .ifPresent(camera -> openIfAbsent(camera.cameraId(), camera.zoneId(), now));
    }

    /**
     * 상태 보고가 RUNNING일 때 부른다. 상태가 RUNNING으로 바뀐 보고(재가동·복구)일 때만 열린 이벤트를 닫고
     * 마지막 결과 시각을 지금으로 올린다. 올리지 않으면 오래된 시각 때문에 다음 검사가 곧바로 이벤트를 다시 연다.
     * 이미 RUNNING인 캠의 반복 보고로는 닫지도 올리지도 않는다. 결과가 끊긴 캠의 이벤트가 닫혔다 다시 열려
     * 장애 하나가 둘로 나뉘거나 기준 시각이 보고마다 밀리는 것을 막고, 결과가 들어온 뒤의 검사에서 닫히게 한다.
     */
    @Transactional
    public void closeOnRecovered(Long cameraId, LocalDateTime now, boolean statusChanged) {
        // 상태가 이미 RUNNING이던 반복 보고면 아무것도 하지 않는다(위 설명 참고)
        if (!statusChanged) {
            return;
        }
        // 복구된 캠의 열린 이벤트를 닫는다
        closeOpenEvents(cameraId, now);
        // 마지막 결과 시각을 지금으로 올려 곧바로 재발생하는 것을 막는다
        lastResultTimeStore.update(cameraId, now);
    }

    /** 캠이 정지되면 열린 이벤트를 닫는다(FNC-A-021 처리 순서 5). */
    @Transactional
    public void closeOnStop(Long cameraId, LocalDateTime now) {
        closeOpenEvents(cameraId, now);
    }

    // PREPARING·STOPPED는 검사하지 않는다. 결과 기준 시각이 없는 캠은 감시 시작 시각을 기준으로 삼고 unknownCameraIds에 담는다
    /**
     * 캠 한 대가 이상인지 판정한다. ERROR면 즉시 이상, RUNNING이면 마지막 결과가 timeout 이상 끊겼을 때 이상이다.
     */
    private boolean isFaulty(WatchedCamera camera, LocalDateTime now, Set<Long> unknownCameraIds) {
        // 캠이 스스로 오류를 보고했으면 시간을 따질 것 없이 이상
        if (camera.status() == CameraStatus.ERROR) {
            return true;
        }
        // 준비 중·정지 상태는 결과가 없는 게 정상이라 이상으로 보지 않는다
        if (camera.status() != CameraStatus.RUNNING) {
            return false;
        }
        // 기준 시각이 없으면 지금을 감시 시작 시각으로 심어, 결과가 한 번도 없는 캠도 timeout 뒤부터 판정되게 한다
        lastResultTimeStore.seedIfAbsent(camera.cameraId(), now);
        // 심은 시각뿐이라면 실제 결과를 받은 적이 없어 정상 여부를 알 수 없으므로 '불명'으로 따로 모은다
        if (lastResultTimeStore.isSeededOnly(camera.cameraId())) {
            unknownCameraIds.add(camera.cameraId());
        }
        // 마지막으로 결과를 받은 시각(없으면 지금으로 봐서 이상으로 판정하지 않는다)
        LocalDateTime lastResultAt =
                lastResultTimeStore.findLastResultAt(camera.cameraId()).orElse(now);
        // 마지막 결과 이후 경과 시간이 timeout 이상이면 이상
        return Duration.between(lastResultAt, now).compareTo(timeout) >= 0;
    }

    /**
     * 같은 캠의 열린 캠 이상 이벤트가 없을 때만 새로 연다. 10초마다 호출되므로 중복 생성을 막기 위한 검사다.
     */
    private void openIfAbsent(Long cameraId, Long zoneId, LocalDateTime now) {
        // 종료 시각이 없는(열린) 캠 이상 이벤트가 이 캠에 이미 있는지 확인
        boolean alreadyOpen = eventRepository
                .findFirstByCameraIdAndTypeAndEndedAtIsNullOrderByIdAsc(cameraId, EventType.CAM_FAULT)
                .isPresent();
        if (alreadyOpen) {
            return;
        }
        // 새 이벤트 저장. 기준값 스냅샷에는 당시 timeout을 남겨 나중에 설정이 바뀌어도 당시 기준을 알 수 있게 한다
        Event created = eventRepository.save(Event.openCamFault(zoneId, cameraId, now, toSnapshotJson()));
        log.info("캠 이상 이벤트 생성 cameraId={} zoneId={} eventId={}", cameraId, zoneId, created.getId());
        // 새 이벤트는 열려 있는 관리자 화면에 알린다(FNC-A-048)
        notificationPublisher.eventCreated(created.getId(), EventType.CAM_FAULT, zoneId, null, now);
        notificationPublisher.uncheckedCountChanged();
    }

    /** 캠의 열린 캠 이상 이벤트를 모두 닫는다. */
    private void closeOpenEvents(Long cameraId, LocalDateTime now) {
        // 열린 이벤트는 캠당 하나가 규칙이지만, 어긋나 둘이 되어도 모두 닫는다
        eventRepository
                .findByCameraIdAndTypeAndEndedAtIsNull(cameraId, EventType.CAM_FAULT)
                .forEach(event -> closeEvent(event, now));
    }

    /** 이벤트에 종료 시각을 기록한다. 영속 상태 엔티티라 트랜잭션이 끝날 때 변경이 저장된다. */
    private void closeEvent(Event event, LocalDateTime now) {
        event.close(now);
        log.info("캠 이상 이벤트 종료 cameraId={} eventId={}", event.getCameraId(), event.getId());
    }

    // 이벤트 발생 당시 기준값(timeout 초)을 JSON 문자열로 만든다
    private String toSnapshotJson() {
        return "{\"timeoutSec\":" + timeoutSec + "}";
    }
}
