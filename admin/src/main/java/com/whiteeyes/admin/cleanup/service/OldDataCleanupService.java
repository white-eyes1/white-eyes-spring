package com.whiteeyes.admin.cleanup.service;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** 오래된 데이터 정리(FNC-A-032) */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
@Log4j2
public class OldDataCleanupService {

    /** 한 번에 지우는 최대 건수(FNC-A-032 처리 순서). 긴 잠금과 큰 트랜잭션을 피한다. */
    public static final int BATCH_SIZE = 10_000;

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final RecordingRepository recordingRepository;
    private final RetentionProperties properties;

    /**
     * 1분 통계를 먼저, 녹화 기록을 그다음 지운다. 한쪽이 실패해도 다른 쪽은 계속한다. 다시 실행해도 안전하다.
     *
     * @param now KST 현재 시각
     */
    public void cleanUp(LocalDateTime now) {
        // 1분 통계는 날짜 단위(오늘 0시 − 보관 일수), 녹화 기록은 현재 시각 − 보관 일수
        LocalDateTime statCutoff = now.toLocalDate().minusDays(properties.statMinuteDays()).atStartOfDay();
        LocalDateTime recordingCutoff = now.minusDays(properties.recordingDays());

        deleteInBatches("1분 통계", statCutoff, () -> zoneStatMinuteRepository.deleteOlderThan(statCutoff, BATCH_SIZE));
        deleteInBatches(
                "녹화 기록", recordingCutoff, () -> recordingRepository.deleteStartedBefore(recordingCutoff, BATCH_SIZE));
    }

    // 배치마다 따로 커밋되므로 중간에 실패해도 그때까지 지운 것은 남고, 다음 날 이어서 지운다
    private void deleteInBatches(String name, LocalDateTime cutoff, IntSupplier deleteBatch) {
        long total = 0;
        try {
            int deleted;
            do {
                deleted = deleteBatch.getAsInt();
                total += deleted;
            } while (deleted == BATCH_SIZE);
            log.info("오래된 {} 삭제 {}건 (기준 {} 이전)", name, total, cutoff);
        } catch (RuntimeException e) {
            log.error("오래된 {} 삭제 실패 (기준 {} 이전, 지운 건수 {})", name, cutoff, total, e);
        }
    }
}
