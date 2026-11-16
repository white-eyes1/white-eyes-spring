package com.whiteeyes.common.stat.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.whiteeyes.common.stat.service.StatRollupCalculator;

/**
 * 구역별 일별 통계(tables.md 'zone_stat_daily'). 관리자 앱이 자정 직후 전날 시간별 통계를 묶어 저장한다(FNC-A-030).
 */
// 일별 통계 테이블과 매핑되는 엔티티. 키는 (zone_id, stat_date) 복합 키라 @IdClass를 쓴다
@Entity
@Table(name = "zone_stat_daily")
@IdClass(ZoneStatDailyId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneStatDaily extends ZoneStatRollup {

    /** 집계 날짜(KST). */
    @Id
    @Column(name = "stat_date")
    private LocalDate statDate;

    // 공통 칼럼은 부모(ZoneStatRollup)가 채우고, 일별만의 키 칼럼(집계 날짜)을 여기서 채운다
    public ZoneStatDaily(Long zoneId, LocalDate statDate, StatValues values, boolean demo) {
        super(zoneId, values, demo);
        this.statDate = statDate;
    }

    /**
     * 한 구역의 그날 시간별 통계를 묶어 일별 행을 만든다(FNC-A-030). 시간별 중 하나라도 시연용이면 일별도 시연용이다.
     * 관리자 앱의 자정 집계와 시연용 통계 생성(tools)이 같은 규칙을 쓰도록 여기에 둔다.
     *
     * @return 받은 초가 있는 시간별이 없으면 빈 값
     */
    public static Optional<ZoneStatDaily> rollUp(Long zoneId, LocalDate statDate, List<ZoneStatHourly> hours) {
        // 시간별 중 하나라도 시연용이면 그날 일별도 시연용으로 표시한다(시연 데이터가 실제 통계처럼 보이지 않게)
        boolean demo = hours.stream().anyMatch(ZoneStatHourly::isDemo);
        // 시간별 행을 StatValues로 바꿔 계산기에 넘긴다. 받은 초가 전혀 없으면 계산기가 빈 값을 돌려주고, 그러면 일별 행도 만들지 않는다
        return StatRollupCalculator.rollUp(
                        hours.stream().map(ZoneStatHourly::values).toList())
                .map(values -> new ZoneStatDaily(zoneId, statDate, values, demo));
    }
}
