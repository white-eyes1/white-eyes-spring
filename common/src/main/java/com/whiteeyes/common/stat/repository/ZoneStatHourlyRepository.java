package com.whiteeyes.common.stat.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatHourlyId;

public interface ZoneStatHourlyRepository extends JpaRepository<ZoneStatHourly, ZoneStatHourlyId> {

    /** 구역들의 from 이상 to 미만 시간별 통계. 통계 시계열 1h(FNC-C-012)가 구역 수와 관계없이 한 번에 읽는다. */
    List<ZoneStatHourly> findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(
            Collection<Long> zoneIds, LocalDateTime from, LocalDateTime to);

    /** from 이상 to 미만 시간별 통계(전 구역). 일별 통계가 하루치를 묶을 때 쓴다(FNC-A-030). */
    List<ZoneStatHourly> findByStatAtGreaterThanEqualAndStatAtLessThan(LocalDateTime from, LocalDateTime to);

    /**
     * date 하루(from~to)에 받은 초가 있는 시간별 행(sample_sec>0)은 있는데 일별 행이 없는 구역 번호(FNC-A-031).
     * 받은 초가 없는 날은 일별 행을 만들 수 없어 대상에서 뺀다.
     */
    @Query("""
            select distinct h.zoneId from ZoneStatHourly h
            where h.statAt >= :from and h.statAt < :to and h.sampleSec > 0
              and not exists (select 1 from ZoneStatDaily d where d.zoneId = h.zoneId and d.statDate = :date)
            """)
    List<Long> findZoneIdsMissingDaily(
            @Param("date") LocalDate date, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 구역들의 from 이상 to 미만 시간별 count_avg를 같은 시각끼리 더한 값(FNC-C-020 처리 순서 1, 지역 합산).
     * 받은 초가 없는 행(sample_sec=0)은 빼므로, 모든 구역이 끊긴 시각은 결과에 없다.
     */
    @Query("""
            select h.statAt as statAt, sum(h.countAvg) as countSum from ZoneStatHourly h
            where h.zoneId in :zoneIds and h.statAt >= :from and h.statAt < :to and h.sampleSec > 0
            group by h.statAt
            """)
    List<HourlyCountSum> sumCountAvgByStatAt(
            @Param("zoneIds") Collection<Long> zoneIds, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 한 시각의 구역 count_avg 합 */
    interface HourlyCountSum {

        LocalDateTime getStatAt();

        BigDecimal getCountSum();
    }
}
