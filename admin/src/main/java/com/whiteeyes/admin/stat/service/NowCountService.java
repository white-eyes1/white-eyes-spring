package com.whiteeyes.admin.stat.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 현재 인원 저장(FNC-A-054). 구역마다 메모리 누적기의 가장 최근 초 인원을 5초마다 zone_now_count에 덮어쓴다.
 * 1분 통계 저장(FNC-A-028)·이벤트 판정과 따로 돌고, 이 값을 그쪽에 넘기지 않는다.
 */
@Service
public class NowCountService {

    private static final Logger log = LoggerFactory.getLogger(NowCountService.class);

    // 갱신 부분은 VALUES() 대신 값을 다시 써서 MySQL 8(VALUES() 사용 중단 예정)과 H2 모두에서 동작하게 한다
    private static final String UPSERT = """
            INSERT INTO zone_now_count (zone_id, person_count, updated_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE person_count = ?, updated_at = ?
            """;

    private final MinuteAccumulatorStore minuteAccumulatorStore;
    private final JdbcTemplate jdbcTemplate;
    private final Duration fresh;

    public NowCountService(
            MinuteAccumulatorStore minuteAccumulatorStore,
            JdbcTemplate jdbcTemplate,
            // 테스트 application.yml이 main 설정을 가리므로 yml을 거치지 않고 환경변수를 바로 읽는다(기본 15초, conventions.md)
            @Value("${NOW_COUNT_FRESH_SEC:15}") long freshSec) {
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.fresh = Duration.ofSeconds(freshSec);
    }

    /**
     * 신선한(now − NOW_COUNT_FRESH_SEC 이후에 찍힌) 구역만 한 배치로 저장한다. 실패하면 로그만 남기고 다음 주기에 다시 쓴다.
     *
     * @param now KST 현재 시각
     * @return 저장하려고 한 구역 수
     */
    public int saveLatest(LocalDateTime now) {
        LocalDateTime oldest = now.minus(fresh);
        Map<Long, MinuteAccumulatorStore.LatestSecond> latestByZone = minuteAccumulatorStore.findLatestSecondByZone();
        List<Object[]> rows = latestByZone.entrySet().stream()
                .filter(entry -> !entry.getValue().second().isBefore(oldest))
                .map(entry -> {
                    Timestamp at = Timestamp.valueOf(entry.getValue().second());
                    int count = entry.getValue().personCount();
                    return new Object[] {entry.getKey(), count, at, count, at};
                })
                .toList();
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            jdbcTemplate.batchUpdate(UPSERT, rows);
        } catch (RuntimeException e) {
            log.error("현재 인원 저장 실패 zones={}", rows.size(), e);
            return 0;
        }
        return rows.size();
    }
}
