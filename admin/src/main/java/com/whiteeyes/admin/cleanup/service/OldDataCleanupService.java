package com.whiteeyes.admin.cleanup.service;

import java.time.LocalDateTime;
import java.util.function.IntSupplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 오래된 데이터 정리(FNC-A-032) */
@Slf4j
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
public class OldDataCleanupService {

    /** 한 번에 지우는 최대 건수(FNC-A-032 처리 순서). 긴 잠금과 큰 트랜잭션을 피한다. */
    public static final int BATCH_SIZE = 10_000;

    // 1분 통계 테이블(공통 모듈).
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 녹화 기록 테이블.
    private final RecordingRepository recordingRepository;
    // 보관 일수 설정(retention.*). @EnableConfigurationProperties가 이 클래스에서 설정 객체를 등록한다.
    private final RetentionProperties properties;

    /**
     * 1분 통계를 먼저, 녹화 기록을 그다음 지운다. 한쪽이 실패해도 다른 쪽은 계속한다. 다시 실행해도 안전하다.
     *
     * @param now KST 현재 시각
     */
    public void cleanUp(LocalDateTime now) {
        // 1분 통계는 날짜 단위(오늘 0시 − 보관 일수), 녹화 기록은 현재 시각 − 보관 일수
        // 이 시각 '이전' 데이터를 지운다. 통계는 날짜 경계로 자르면 하루치가 도중에 잘려 나가지 않는다.
        LocalDateTime statCutoff =
                now.toLocalDate().minusDays(properties.statMinuteDays()).atStartOfDay();
        // 녹화는 시작 시각 기준 보관 기간. 시각 단위로 계산한다.
        LocalDateTime recordingCutoff = now.minusDays(properties.recordingDays());

        // 두 정리는 서로 독립이다(deleteInBatches가 예외를 삼켜 기록만 남기므로 앞이 실패해도 뒤가 실행된다).
        deleteInBatches("1분 통계", statCutoff, () -> zoneStatMinuteRepository.deleteOlderThan(statCutoff, BATCH_SIZE));
        deleteInBatches(
                "녹화 기록", recordingCutoff, () -> recordingRepository.deleteStartedBefore(recordingCutoff, BATCH_SIZE));
    }

    // 배치마다 따로 커밋되므로 중간에 실패해도 그때까지 지운 것은 남고, 다음 날 이어서 지운다
    /**
     * 한 번에 BATCH_SIZE건씩 지우는 것을 더 지울 게 없을 때까지 반복한다.
     *
     * @param name 로그에 쓸 대상 이름
     * @param cutoff 기준 시각(로그용)
     * @param deleteBatch 한 배치를 지우고 지운 건수를 돌려주는 함수
     */
    private void deleteInBatches(String name, LocalDateTime cutoff, IntSupplier deleteBatch) {
        // 지금까지 지운 건수 합계(로그용).
        long total = 0;
        try {
            int deleted;
            do {
                deleted = deleteBatch.getAsInt();
                total += deleted;
                // 꽉 찬 배치(BATCH_SIZE건)면 아직 남았을 수 있어 한 번 더 지운다. 그보다 적게 지웠으면 끝난 것이다.
            } while (deleted == BATCH_SIZE);
            log.info("오래된 {} 삭제 {}건 (기준 {} 이전)", name, total, cutoff);
            // 실패해도 예외를 밖으로 던지지 않는다: 스케줄러가 계속 돌고, 다른 대상 정리도 이어서 할 수 있게 한다.
        } catch (RuntimeException e) {
            log.error("오래된 {} 삭제 실패 (기준 {} 이전, 지운 건수 {})", name, cutoff, total, e);
        }
    }
}
