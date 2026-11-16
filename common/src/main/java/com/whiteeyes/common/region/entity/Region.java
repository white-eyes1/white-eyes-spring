package com.whiteeyes.common.region.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * 지역(region 테이블). 시·도 → 시·군·구 → 읍·면·동의 3단계 계층이 한 테이블에 들어 있고 parent로 위 단계를 가리킨다.
 * 경계(boundary)는 GeoJSON 문자열이고 행정 경계 파일을 적재해서 채운다(FNC-A-012).
 */
@Entity
@Table(name = "region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

    // 기본키. DB 자동 증가 값
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 위 단계 지역. 시·도는 null. 필요할 때만 읽도록 지연 로딩이라 목록 조회에서 한꺼번에 읽으려면 fetch join 쿼리를 쓴다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Region parent;

    // 단계(SIDO·SIGUNGU·EMD). 이름 문자열로 저장한다
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RegionLevel level;

    // 행정구역 코드. 중복 불가이고 같은 코드는 다시 적재할 때 덮어쓰는 기준이 된다. 하위 지역 코드가 상위 코드로 시작하는 성질을 찾기에 이용한다
    @Column(nullable = false, length = 10, unique = true)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    // 경계(GeoJSON)를 JSON 문자열로 저장한다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String boundary;

    // 중심 위도. 소수 6자리 정밀도(약 0.1m)의 BigDecimal
    @Column(name = "center_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLat;

    // 중심 경도
    @Column(name = "center_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLng;

    // DB 기본값(CURRENT_TIMESTAMP)이 채운다. 다시 적재해도 처음 적재 시각을 유지한다.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // 새 지역을 만든다(경계 파일 적재 시)
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
