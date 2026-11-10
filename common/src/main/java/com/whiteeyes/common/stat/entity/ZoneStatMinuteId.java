package com.whiteeyes.common.stat.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * zone_stat_minute의 복합 키(zone_id, stat_at).
 */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ZoneStatMinuteId implements Serializable {

    private Long zoneId;
    private LocalDateTime statAt;
}
