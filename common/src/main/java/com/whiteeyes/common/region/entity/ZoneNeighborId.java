package com.whiteeyes.common.region.entity;

import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** zone_neighbor의 복합 키(zone_id, direction). */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
public class ZoneNeighborId implements Serializable {

    private Long zoneId;
    private ZoneDirection direction;

    public ZoneNeighborId(Long zoneId, ZoneDirection direction) {
        this.zoneId = zoneId;
        this.direction = direction;
    }
}
