package com.whiteeyes.common.stat.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
}
