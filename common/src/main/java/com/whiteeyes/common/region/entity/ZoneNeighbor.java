package com.whiteeyes.common.region.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 구역의 방향별 이웃(tables.md zone_neighbor). 행이 없는 방향은 '관측 범위 밖'이다. */
@Entity
@Table(name = "zone_neighbor")
@IdClass(ZoneNeighborId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneNeighbor {

    // 구역 엔티티를 직접 들지 않고 번호만 둔다(package-structure.md: 다른 도메인 엔티티 직접 사용 금지)
    @Id
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 1)
    private ZoneDirection direction;

    @Column(name = "neighbor_id", nullable = false)
    private Long neighborId;

    public ZoneNeighbor(Long zoneId, ZoneDirection direction, Long neighborId) {
        this.zoneId = zoneId;
        this.direction = direction;
        this.neighborId = neighborId;
    }

    public void changeNeighbor(Long neighborId) {
        this.neighborId = neighborId;
    }
}
