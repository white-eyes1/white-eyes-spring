package com.whiteeyes.common.stat.entity;

import java.io.Serializable;
import java.time.LocalDate;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * zone_stat_daily의 복합 키(zone_id, stat_date).
 */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ZoneStatDailyId implements Serializable {

    private Long zoneId;
    private LocalDate statDate;
}
