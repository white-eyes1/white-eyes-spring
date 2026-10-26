package com.whiteeyes.admin.notification.service;

import com.whiteeyes.admin.notification.dto.CameraStatusMessage;
import com.whiteeyes.admin.notification.dto.EventCreatedMessage;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.service.UncheckedEventCountService;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관리자 알림 스트림(FNC-A-048)에 알림을 내보내는 notification 도메인의 공개 service. 이벤트 판정(FNC-A-033~035),
 * 처리 상태 변경(FNC-A-038), 캠 상태 보고(FNC-A-025)가 값만 넘겨 부른다(다른 도메인의 엔티티·리포지토리를 받지 않는다).
 *
 * <p>트랜잭션 안에서 불리면 커밋된 뒤에 보낸다. 롤백된 저장을 화면에 알리지 않고, 알림을 받은 화면이 바로 조회해도
 * 저장된 값을 보게 하려는 것이다. 보내는 일은 별도 스레드 하나가 부른 순서대로 처리해서 DB 조회(구역 이름·미확인 수)가
 * 부른 쪽의 트랜잭션에 섞이지 않고, 알림이 늦거나 실패해도 부른 쪽의 저장은 영향을 받지 않는다(실패하면 로그만 남긴다).
 */
@Slf4j
@Service
public class NotificationPublisher {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ObjectProvider<NotificationBroadcaster> broadcasterProvider;
    private final ZoneService zoneService;
    private final UncheckedEventCountService uncheckedEventCountService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notification-publish");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationPublisher(
            // 웹 서버 없이 띄우는 실행(--job=…)에는 브로드캐스터가 없어 알림을 건너뛴다
            ObjectProvider<NotificationBroadcaster> broadcasterProvider,
            ZoneService zoneService,
            UncheckedEventCountService uncheckedEventCountService) {
        this.broadcasterProvider = broadcasterProvider;
        this.zoneService = zoneService;
        this.uncheckedEventCountService = uncheckedEventCountService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 새 이벤트를 알린다(event-created). 이벤트를 저장한 쪽이 부른다.
     *
     * @param level 혼잡 이벤트의 최고 단계. 급증·캠 이상은 null
     * @param startedAt 이벤트 발생 시각(KST)
     */
    public void eventCreated(long eventId, EventType type, long zoneId, Integer level, LocalDateTime startedAt) {
        afterCommit(() -> {
            EventCreatedMessage message = new EventCreatedMessage(
                    eventId, type, zoneNameOf(zoneId), level, startedAt.atZone(KST).toOffsetDateTime());
            broadcaster().broadcast("event-created", message);
        });
    }

    /** 미확인 수가 바뀌었음을 알린다(unchecked-count). 보내는 시점의 미확인 수를 읽어 보낸다. */
    public void uncheckedCountChanged() {
        afterCommit(() -> broadcaster().broadcast("unchecked-count", uncheckedEventCountService.count()));
    }

    /** 캠 상태·진행률·오류 내용이 바뀌었음을 알린다(camera-status). */
    public void cameraStatusChanged(long cameraId, CameraStatus status, Integer progress, String errorMessage) {
        afterCommit(() -> broadcaster()
                .broadcast("camera-status", new CameraStatusMessage(cameraId, status, progress, errorMessage)));
    }

    private NotificationBroadcaster broadcaster() {
        return broadcasterProvider.getObject();
    }

    // 지워진 구역이어도 이름은 남아 있다. 구역 행이 없으면 번호로 대신한다
    private String zoneNameOf(long zoneId) {
        ZoneSummary zone = zoneService.findSummaries(Set.of(zoneId)).get(zoneId);
        return zone == null ? "구역 " + zoneId : zone.name();
    }

    private void afterCommit(Runnable job) {
        if (broadcasterProvider.getIfAvailable() == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(job);
                }
            });
            return;
        }
        submit(job);
    }

    private void submit(Runnable job) {
        try {
            executor.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    // 알림이 실패해도 이미 저장한 이벤트·상태는 그대로 두고, 화면은 다음 알림이나 새로 고침으로 맞춘다
                    log.warn("관리자 알림을 보내지 못했습니다.", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 앱을 내리는 중이다
            log.debug("앱을 내리는 중이라 알림을 건너뜁니다.");
        }
    }
}
