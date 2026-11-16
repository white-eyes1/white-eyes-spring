package com.whiteeyes.common.region.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * 구역(zone 테이블). 읍·면·동 아래에 관리자가 그린 다각형 영역이고 캠 한 대가 관측한다. 삭제된 구역은 행을 지우지 않고 deleted_at만 채운다.
 */
@Entity
@Table(name = "zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {

    // 기본키. DB 자동 증가 값
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // region 도메인의 엔티티를 직접 들지 않고 번호만 둔다(package-structure.md: 다른 도메인 엔티티 직접 사용 금지)
    @Column(name = "dong_id", nullable = false)
    private Long dongId;

    @Column(nullable = false, length = 50)
    private String name;

    // 구역 면적(㎡). 밀집도(명/㎡) 계산의 분모가 된다
    @Column(name = "area_m2", nullable = false, precision = 10, scale = 2)
    private BigDecimal areaM2;

    // 다각형 경계를 GeoJSON JSON 문자열로 저장한다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String boundary;

    @Column(name = "center_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLat;

    @Column(name = "center_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal centerLng;

    // 삭제 표시 시각. 값이 있으면 삭제된 구역이다(과거 통계·다시보기가 이 구역을 가리키므로 행은 남긴다)
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 아래 두 시각은 DB 기본값(CURRENT_TIMESTAMP, updated_at은 ON UPDATE)이 채운다
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /** 삭제 표시를 한다(가동 이력이 있는 구역 삭제, FNC-A-017). 이미 지워진 구역은 호출하는 쪽이 먼저 걸러 낸다. */
    public void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
    }

    /** 이름을 바꾼다(FNC-A-015). 같은 동의 이름 중복은 호출하는 쪽이 먼저 확인한다. */
    public void rename(String name) {
        this.name = name;
    }

    /** 다각형을 바꾸고 그에 따라 다시 계산한 면적·중심점을 넣는다(FNC-A-015). 소속 동은 바뀌지 않는다. */
    public void reshape(BigDecimal areaM2, String boundary, BigDecimal centerLat, BigDecimal centerLng) {
        this.areaM2 = areaM2;
        this.boundary = boundary;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
    }

    // 새 구역을 만든다. 삭제 시각은 비워 둔 상태로 시작한다
    public Zone(
            Long dongId, String name, BigDecimal areaM2, String boundary, BigDecimal centerLat, BigDecimal centerLng) {
        this.dongId = dongId;
        this.name = name;
        this.areaM2 = areaM2;
        this.boundary = boundary;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
    }
}
