package com.whiteeyes.admin.event.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 알림(SSE)은 여기서 하지 않는다.
 */
@Service
public class CamFaultEventService {

    private static final Logger log = LoggerFactory.getLogger(CamFaultEventService.class);

    private final WatchedCameraService watchedCameraService;
    private final EventRepository eventRepository;
    private final LastResultTimeStore lastResultTimeStore;
    private final long timeoutSec;
    private final Duration timeout;

    public CamFaultEventService(
            WatchedCameraService watchedCameraService,
            EventRepository eventRepository,
            LastResultTimeStore lastResultTimeStore,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본값 conventions.md)
            @Value("${CAM_FAULT_TIMEOUT_SEC:60}") long timeoutSec) {
        this.watchedCameraService = watchedCameraService;
        this.eventRepository = eventRepository;
        this.lastResultTimeStore = lastResultTimeStore;
        this.timeoutSec = timeoutSec;
        this.timeout = Duration.ofSeconds(timeoutSec);
    }

    /** 감시 대상 캠을 모두 검사해 이벤트를 열고, 조건이 풀렸거나 대상에서 빠진 캠의 열린 이벤트를 닫는다. */
    @Transactional
    public void detect(LocalDateTime now) {
        List<WatchedCamera> watched = watchedCameraService.findWatchedCameras();
        Set<Long> faultyCameraIds = new HashSet<>();
        // 기준 시각이 감시 시작 시각뿐인 캠(재시작 직후 등): 결과를 한 번도 못 받아 정상인지 알 수 없다
        Set<Long> unknownCameraIds = new HashSet<>();
        for (WatchedCamera camera : watched) {
            if (isFaulty(camera, now, unknownCameraIds)) {
                faultyCameraIds.add(camera.cameraId());
                openIfAbsent(camera.cameraId(), camera.zoneId(), now);
            }
        }
        // 조건이 풀린 캠과 정지·삭제로 대상에서 빠진 캠은 faultyCameraIds에 없으므로 한 번에 닫힌다.
        // 단 정상인지 알 수 없는 캠(unknownCameraIds)의 열린 이벤트는 닫지 않고 둔다(결과가 오면 다음 검사에서 닫힌다)
        int closed = 0;
        for (Event event : eventRepository.findByTypeAndEndedAtIsNull(EventType.CAM_FAULT)) {
            if (!faultyCameraIds.contains(event.getCameraId()) && !unknownCameraIds.contains(event.getCameraId())) {
                closeEvent(event, now);
                closed++;
            }
        }
        log.debug("캠 이상 검사 대상={} 이상={} 닫음={}", watched.size(), faultyCameraIds.size(), closed);
    }

    /** 상태 보고가 ERROR일 때 부른다. run_requested=1인 캠만 이벤트를 연다(FNC-A-025 처리 순서 3). */
    @Transactional
    public void openOnError(Long cameraId, LocalDateTime now) {
        watchedCameraService
                .findWatchedCamera(cameraId)
                .ifPresent(camera -> openIfAbsent(camera.cameraId(), camera.zoneId(), now));
    }

    /**
     * 상태 보고가 RUNNING일 때 부른다. 열린 이벤트를 닫는다.
     * 상태가 RUNNING으로 바뀐 보고(재가동·복구)면 마지막 결과 시각도 지금으로 올린다. 올리지 않으면 오래된 시각 때문에
     * 다음 검사가 곧바로 이벤트를 다시 연다. 이미 RUNNING인 캠의 반복 보고로는 올리지 않는다. 결과가 끊긴 캠의 기준
     * 시각이 보고마다 밀리면 이벤트가 열리지 않기 때문이다.
     */
    @Transactional
    public void closeOnRecovered(Long cameraId, LocalDateTime now, boolean statusChanged) {
        closeOpenEvents(cameraId, now);
        if (statusChanged) {
            lastResultTimeStore.update(cameraId, now);
        }
    }

    /** 캠이 정지되면 열린 이벤트를 닫는다(FNC-A-021 처리 순서 5). */
    @Transactional
    public void closeOnStop(Long cameraId, LocalDateTime now) {
        closeOpenEvents(cameraId, now);
    }

    // PREPARING·STOPPED는 검사하지 않는다. 결과 기준 시각이 없는 캠은 감시 시작 시각을 기준으로 삼고 unknownCameraIds에 담는다
    private boolean isFaulty(WatchedCamera camera, LocalDateTime now, Set<Long> unknownCameraIds) {
        if (camera.status() == CameraStatus.ERROR) {
            return true;
        }
        if (camera.status() != CameraStatus.RUNNING) {
            return false;
        }
        lastResultTimeStore.seedIfAbsent(camera.cameraId(), now);
        if (lastResultTimeStore.isSeededOnly(camera.cameraId())) {
            unknownCameraIds.add(camera.cameraId());
        }
        LocalDateTime lastResultAt =
                lastResultTimeStore.findLastResultAt(camera.cameraId()).orElse(now);
        return Duration.between(lastResultAt, now).compareTo(timeout) >= 0;
    }

    private void openIfAbsent(Long cameraId, Long zoneId, LocalDateTime now) {
        boolean alreadyOpen = eventRepository
                .findFirstByCameraIdAndTypeAndEndedAtIsNullOrderByIdAsc(cameraId, EventType.CAM_FAULT)
                .isPresent();
        if (alreadyOpen) {
            return;
        }
        Event created = eventRepository.save(Event.openCamFault(zoneId, cameraId, now, toSnapshotJson()));
        log.info("캠 이상 이벤트 생성 cameraId={} zoneId={} eventId={}", cameraId, zoneId, created.getId());
    }

    private void closeOpenEvents(Long cameraId, LocalDateTime now) {
        // 열린 이벤트는 캠당 하나가 규칙이지만, 어긋나 둘이 되어도 모두 닫는다
        eventRepository
                .findByCameraIdAndTypeAndEndedAtIsNull(cameraId, EventType.CAM_FAULT)
                .forEach(event -> closeEvent(event, now));
    }

    private void closeEvent(Event event, LocalDateTime now) {
        event.close(now);
        log.info("캠 이상 이벤트 종료 cameraId={} eventId={}", event.getCameraId(), event.getId());
    }

    private String toSnapshotJson() {
        return "{\"timeoutSec\":" + timeoutSec + "}";
    }
}
