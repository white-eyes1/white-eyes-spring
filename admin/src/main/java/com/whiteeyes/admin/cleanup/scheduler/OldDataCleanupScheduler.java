package com.whiteeyes.admin.cleanup.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.cleanup.service.OldDataCleanupService;

/** 오래된 데이터 정리(FNC-A-032) */
@Component
public class OldDataCleanupScheduler {

    private static final String KST_ID = "Asia/Seoul";

    private final OldDataCleanupService cleanupService;
    private final Clock clock;

    public OldDataCleanupScheduler(OldDataCleanupService cleanupService, Clock clock) {
        this.cleanupService = cleanupService;
        this.clock = clock;
    }

    // 매일 03:00(FNC-A-032)
    @Scheduled(cron = "0 0 3 * * *", zone = KST_ID)
    public void cleanUp() {
        cleanupService.cleanUp(LocalDateTime.now(clock));
    }
}
