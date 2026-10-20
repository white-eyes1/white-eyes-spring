package com.whiteeyes.common.region.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Region parent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RegionLevel level;

    @Column(nullable = false, length = 10, unique = true)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String boundary;

    @Column(name = "center_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLat;

    @Column(name = "center_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLng;

    // DB 기본값(CURRENT_TIMESTAMP)이 채운다. 다시 적재해도 처음 적재 시각을 유지한다.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Region(
            Region parent,
            RegionLevel level,
            String code,
            String name,
            String boundary,
            BigDecimal centerLat,
            BigDecimal centerLng) {
        this.parent = parent;
        this.level = level;
        this.code = code;
        this.name = name;
        this.boundary = boundary;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
    }

    /** 경계 파일을 다시 적재할 때 같은 code의 행을 새 값으로 덮어쓴다(FNC-A-012). */
    public void overwrite(
            Region parent,
            RegionLevel level,
            String name,
            String boundary,
            BigDecimal centerLat,
            BigDecimal centerLng) {
        this.parent = parent;
        this.level = level;
        this.name = name;
        this.boundary = boundary;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
    }
}
