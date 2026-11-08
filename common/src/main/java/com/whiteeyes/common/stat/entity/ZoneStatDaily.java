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
        boolean demo = hours.stream().anyMatch(ZoneStatHourly::isDemo);
        return StatRollupCalculator.rollUp(
                        hours.stream().map(ZoneStatHourly::values).toList())
                .map(values -> new ZoneStatDaily(zoneId, statDate, values, demo));
    }
}
