package com.whiteeyes.common.zone.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // region 도메인의 엔티티를 직접 들지 않고 번호만 둔다(package-structure.md: 다른 도메인 엔티티 직접 사용 금지)
    @Column(name = "dong_id", nullable = false)
    private Long dongId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "area_m2", nullable = false, precision = 10, scale = 2)
    private BigDecimal areaM2;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String boundary;

    @Column(name = "center_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLat;

    @Column(name = "center_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLng;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 아래 두 시각은 DB 기본값(CURRENT_TIMESTAMP, updated_at은 ON UPDATE)이 채운다
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Zone(
            Long dongId,
            String name,
            BigDecimal areaM2,
            String boundary,
            BigDecimal centerLat,
            BigDecimal centerLng) {
        this.dongId = dongId;
        this.name = name;
        this.areaM2 = areaM2;
        this.boundary = boundary;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
    }
}
