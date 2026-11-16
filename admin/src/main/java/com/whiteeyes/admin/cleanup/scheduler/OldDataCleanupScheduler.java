package com.whiteeyes.admin.cleanup.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.cleanup.service.OldDataCleanupService;

/** 오래된 데이터 정리(FNC-A-032) */
@Component
@RequiredArgsConstructor
public class OldDataCleanupScheduler {

    // 스케줄 cron을 해석할 시간대. 서버 기본 시간대와 상관없이 한국 시간 03:00에 돌게 한다.
    private static final String KST_ID = "Asia/Seoul";

    // 실제 삭제 로직은 서비스가 맡고, 이 클래스는 '언제 부를지'만 정한다.
    private final OldDataCleanupService cleanupService;
    // 현재 시각을 주입받아 쓰기 위한 Clock(ClockConfig). 시험에서 시각을 고정할 수 있다.
    private final Clock clock;

    // 매일 03:00(FNC-A-032)
    /**
     * 매일 새벽 3시에 오래된 데이터 정리를 한 번 실행한다. 사용자가 적은 시간대라 대량 삭제 부담이 덜하다.
     * 예외는 서비스 안에서 처리되므로 스케줄러 스레드가 죽지 않는다.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = KST_ID)
    public void cleanUp() {
        // Clock 기준 KST 현재 시각을 넘긴다.
        cleanupService.cleanUp(LocalDateTime.now(clock));
    }
}
