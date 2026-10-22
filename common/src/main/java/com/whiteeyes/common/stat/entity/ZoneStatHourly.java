package com.whiteeyes.common.stat.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구역별 시간별 통계(tables.md 'zone_stat_hourly'). 관리자 앱이 정시 직후 1분 통계를 묶어 저장한다(FNC-A-029).
 */
@Entity
@Table(name = "zone_stat_hourly")
@IdClass(ZoneStatHourlyId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneStatHourly extends ZoneStatRollup {

    /** 1시간 구간 시작 시각(KST, 정시). */
    @Id
    @Column(name = "stat_at")
    private LocalDateTime statAt;

    public ZoneStatHourly(Long zoneId, LocalDateTime statAt, StatValues values, boolean demo) {
        super(zoneId, values, demo);
        this.statAt = statAt;
    }
}
