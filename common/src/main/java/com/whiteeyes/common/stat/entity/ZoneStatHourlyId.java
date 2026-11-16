package com.whiteeyes.common.stat.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * zone_stat_hourly의 복합 키(zone_id, stat_at).
 */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ZoneStatHourlyId implements Serializable {

    // 구역 번호(복합 키의 앞부분)
    private Long zoneId;
    // 1시간 구간 시작 시각(복합 키의 뒷부분). 필드 이름은 엔티티의 @Id 필드 이름과 같아야 JPA가 매핑한다
    private LocalDateTime statAt;
}
