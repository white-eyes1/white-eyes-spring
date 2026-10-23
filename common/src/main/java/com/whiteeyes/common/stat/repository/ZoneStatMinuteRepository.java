package com.whiteeyes.common.stat.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.entity.ZoneStatMinuteId;

public interface ZoneStatMinuteRepository extends JpaRepository<ZoneStatMinute, ZoneStatMinuteId> {

    /** 그 구역의 행이 하나라도 있는지(구역 삭제 FNC-A-017 처리 순서 2의 이력 확인). */
    boolean existsByZoneId(Long zoneId);

    /** 저장된 가장 최근 1분 통계 시각. 통계가 하나도 없으면 null(실시간 통계 갱신 FNC-C-022가 5초마다 본다). */
    @Query("select max(s.statAt) from ZoneStatMinute s")
    LocalDateTime findLatestStatAt();

    /** 그 분의 모든 구역 통계(FNC-C-022). */
    List<ZoneStatMinute> findByStatAt(LocalDateTime statAt);

    /** 구역들의 from 이후(포함) 1분 통계. 최근 현황 조회(FNC-C-009)에 쓴다. */
    List<ZoneStatMinute> findByZoneIdInAndStatAtGreaterThanEqual(Collection<Long> zoneIds, LocalDateTime from);

    /** from 이상 to 미만 1분 통계(전 구역). 시간별 통계가 한 시간치를 묶을 때 쓴다(FNC-A-029). */
    List<ZoneStatMinute> findByStatAtGreaterThanEqualAndStatAtLessThan(LocalDateTime from, LocalDateTime to);

    /**
     * from(정시) 한 시간에 받은 초가 있는 1분 행(sample_sec>0)은 있는데 시간별 행이 없는 구역 번호(FNC-A-031).
     * 받은 초가 없는 시간은 시간별 행을 만들 수 없어 대상에서 뺀다. 안 그러면 매번 다시 집계하려다 건너뛰게 된다.
     */
    @Query("""
            select distinct m.zoneId from ZoneStatMinute m
            where m.statAt >= :from and m.statAt < :to and m.sampleSec > 0
              and not exists (select 1 from ZoneStatHourly h where h.zoneId = m.zoneId and h.statAt = :from)
            """)
    List<Long> findZoneIdsMissingHourly(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구역들의 from 이후 1분 통계를 같은 시각끼리 더한 count_max 합들. 오늘 최대 인원(FNC-C-016)에 쓴다. */
    @Query("select sum(s.countMax) from ZoneStatMinute s where s.zoneId in :zoneIds and s.statAt >= :from group by s.statAt")
    List<Long> sumCountMaxByStatAt(@Param("zoneIds") Collection<Long> zoneIds, @Param("from") LocalDateTime from);

    /**
     * stat_at이 cutoff보다 앞선 1분 통계를 최대 limit건 지운다(FNC-A-032).
     *
     * @return 지운 건수
     */
    // 건수 제한이 있는 삭제는 JPQL로 쓸 수 없어 네이티브로 쓴다(MySQL·H2 모두 DELETE ... LIMIT 지원)
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM zone_stat_minute WHERE stat_at < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * (zone_id, stat_at) 기준으로 넣거나 덮어쓴다(FNC-A-028 처리 순서 3).
     * 갱신 부분은 VALUES() 대신 값을 다시 써서 MySQL 8(VALUES() 사용 중단 예정)과 H2 모두에서 동작하게 한다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value = """
                    INSERT INTO zone_stat_minute (
                        zone_id, stat_at, count_avg, count_max, count_min, density,
                        in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w,
                        dwell_sum_sec, dwell_count, sample_sec)
                    VALUES (
                        :#{#stat.zoneId}, :#{#stat.statAt}, :#{#stat.countAvg}, :#{#stat.countMax},
                        :#{#stat.countMin}, :#{#stat.density},
                        :#{#stat.inN}, :#{#stat.inE}, :#{#stat.inS}, :#{#stat.inW},
                        :#{#stat.outN}, :#{#stat.outE}, :#{#stat.outS}, :#{#stat.outW},
                        :#{#stat.dwellSumSec}, :#{#stat.dwellCount}, :#{#stat.sampleSec})
                    ON DUPLICATE KEY UPDATE
                        count_avg = :#{#stat.countAvg}, count_max = :#{#stat.countMax},
                        count_min = :#{#stat.countMin}, density = :#{#stat.density},
                        in_n = :#{#stat.inN}, in_e = :#{#stat.inE}, in_s = :#{#stat.inS}, in_w = :#{#stat.inW},
                        out_n = :#{#stat.outN}, out_e = :#{#stat.outE}, out_s = :#{#stat.outS}, out_w = :#{#stat.outW},
                        dwell_sum_sec = :#{#stat.dwellSumSec}, dwell_count = :#{#stat.dwellCount},
                        sample_sec = :#{#stat.sampleSec}
                    """,
            nativeQuery = true)
    void upsert(@Param("stat") ZoneStatMinute stat);
}
