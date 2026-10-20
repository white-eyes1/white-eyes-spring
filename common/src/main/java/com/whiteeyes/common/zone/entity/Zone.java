package com.whiteeyes.common.zone.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TODO(#10): 구역 등록(FNC-A-014)에서 tables.md 'zone'의 전체 칼럼으로 정식으로 만든다.
// 지금은 FNC-A-028의 밀집도 계산에 필요한 면적만 둔 임시 코드
@Entity
@Table(name = "zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "area_m2", nullable = false, precision = 10, scale = 2)
    private BigDecimal areaM2;
}
