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
// 기본키가 (구역 번호, 방향) 두 칸이라 복합 키 클래스를 쓴다
@IdClass(ZoneNeighborId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneNeighbor {

    // 구역 엔티티를 직접 들지 않고 번호만 둔다(package-structure.md: 다른 도메인 엔티티 직접 사용 금지)
    // 복합 키의 첫 칸: 기준 구역
    @Id
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Id
    @Enumerated(EnumType.STRING)
    // 방향은 'N','E','S','W' 한 글자라 DB의 CHAR(1) 칸에 맞춰 매핑한다
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 1)
    private ZoneDirection direction;

    // 그 방향에 있는 이웃 구역 번호
    @Column(name = "neighbor_id", nullable = false)
    private Long neighborId;

    // 새 이웃 관계를 만든다
    public ZoneNeighbor(Long zoneId, ZoneDirection direction, Long neighborId) {
        this.zoneId = zoneId;
        this.direction = direction;
        this.neighborId = neighborId;
    }

    /** 같은 방향의 이웃 구역을 다른 구역으로 바꾼다. */
    public void changeNeighbor(Long neighborId) {
        this.neighborId = neighborId;
    }
}
