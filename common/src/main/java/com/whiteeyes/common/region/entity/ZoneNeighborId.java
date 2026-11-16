package com.whiteeyes.common.region.entity;

import java.io.Serializable;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** zone_neighbor의 복합 키(zone_id, direction). */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA가 복합 키를 비교·캐시할 때 equals·hashCode가 필요하다
@EqualsAndHashCode
// JPA 규격상 복합 키 클래스는 Serializable이어야 하고, 필드 이름·타입은 엔티티의 @Id 필드와 같아야 한다
public class ZoneNeighborId implements Serializable {

    private Long zoneId;
    private ZoneDirection direction;

    public ZoneNeighborId(Long zoneId, ZoneDirection direction) {
        this.zoneId = zoneId;
        this.direction = direction;
    }
}
