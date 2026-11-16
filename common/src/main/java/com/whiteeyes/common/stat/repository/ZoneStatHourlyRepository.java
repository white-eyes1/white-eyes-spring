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

// 시간별 통계 조회용 저장소. 메서드 이름만으로 쿼리가 만들어지는 Spring Data 규칙을 쓴다
public interface ZoneStatHourlyRepository extends JpaRepository<ZoneStatHourly, ZoneStatHourlyId> {

    /** 구역들의 from 이상 to 미만 시간별 통계. 통계 시계열 1h(FNC-C-012)가 구역 수와 관계없이 한 번에 읽는다. */
    List<ZoneStatHourly> findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(
            Collection<Long> zoneIds, LocalDateTime from, LocalDateTime to);

    /** 그 구역의 행이 하나라도 있는지(구역 삭제 FNC-A-017 처리 순서 2의 이력 확인). */
    boolean existsByZoneId(Long zoneId);

    /** from 이상 to 미만 시간별 통계(전 구역). 일별 통계가 하루치를 묶을 때 쓴다(FNC-A-030). */
    List<ZoneStatHourly> findByStatAtGreaterThanEqualAndStatAtLessThan(LocalDateTime from, LocalDateTime to);

    /**
     * date 하루(from~to)에 받은 초가 있는 시간별 행(sample_sec>0)은 있는데 일별 행이 없는 구역 번호(FNC-A-031).
     * 받은 초가 없는 날은 일별 행을 만들 수 없어 대상에서 뺀다.
     */
    // JPQL: 하루 안에 시간별 행은 있는데 일별 행이 없는 구역을 찾는다. not exists 서브쿼리로 이미 만든 구역을 걸러내고,
    // distinct로 한 구역이 여러 시간대에서 중복되지 않게 한다. 매개변수 :date가 일별 키, :from~:to가 그날의 시각 범위다
    @Query(
            """
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
    // JPQL: 같은 시각(stat_at)끼리 구역들의 평균 인원을 더한다. 지역 하나를 구역 합산으로 비교할 때 쓴다.
    // 인터페이스 기반 프로젝션(HourlyCountSum)으로 별칭(statAt, countSum)과 게터 이름을 맞춰 받는다
    @Query(
            """
            select h.statAt as statAt, sum(h.countAvg) as countSum from ZoneStatHourly h
            where h.zoneId in :zoneIds and h.statAt >= :from and h.statAt < :to and h.sampleSec > 0
            group by h.statAt
            """)
    List<HourlyCountSum> sumCountAvgByStatAt(
            @Param("zoneIds") Collection<Long> zoneIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 한 시각의 구역 count_avg 합 */
    // Spring Data가 쿼리 결과를 이 인터페이스로 감싸 돌려준다(별도 클래스 없이 칼럼 별칭 → 게터로 매핑)
    interface HourlyCountSum {

        LocalDateTime getStatAt();

        BigDecimal getCountSum();
    }
}
