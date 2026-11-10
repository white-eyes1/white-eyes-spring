package com.whiteeyes.common.stat.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구역별 1분 통계(tables.md 'zone_stat_minute'). 관리자 앱의 1분 마감(FNC-A-028)이 저장한다.
 */
@Entity
@Table(name = "zone_stat_minute")
@IdClass(ZoneStatMinuteId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneStatMinute {

    // Zone 엔티티가 정식으로 생기기 전이라 id 값으로 둔다
    @Id
    @Column(name = "zone_id")
    private Long zoneId;

    /** 1분 구간 시작 시각(KST, 초는 0). */
    @Id
    @Column(name = "stat_at")
    private LocalDateTime statAt;

    @Column(name = "count_avg", nullable = false, precision = 7, scale = 2)
    private BigDecimal countAvg;

    @Column(name = "count_max", nullable = false)
    private int countMax;

    @Column(name = "count_min", nullable = false)
    private int countMin;

    @Column(name = "density", nullable = false, precision = 8, scale = 4)
    private BigDecimal density;

    @Column(name = "in_n", nullable = false)
    private int inN;

    @Column(name = "in_e", nullable = false)
    private int inE;

    @Column(name = "in_s", nullable = false)
    private int inS;

    @Column(name = "in_w", nullable = false)
    private int inW;

    @Column(name = "out_n", nullable = false)
    private int outN;

    @Column(name = "out_e", nullable = false)
    private int outE;

    @Column(name = "out_s", nullable = false)
    private int outS;

    @Column(name = "out_w", nullable = false)
    private int outW;

    @Column(name = "dwell_sum_sec", nullable = false)
    private int dwellSumSec;

    @Column(name = "dwell_count", nullable = false)
    private int dwellCount;

    @Column(name = "sample_sec", nullable = false)
    private short sampleSec;

    @Builder
    private ZoneStatMinute(
            Long zoneId,
            LocalDateTime statAt,
            BigDecimal countAvg,
            int countMax,
            int countMin,
            BigDecimal density,
            int inN,
            int inE,
            int inS,
            int inW,
            int outN,
            int outE,
            int outS,
            int outW,
            int dwellSumSec,
            int dwellCount,
            short sampleSec) {
        this.zoneId = zoneId;
        this.statAt = statAt;
        this.countAvg = countAvg;
        this.countMax = countMax;
        this.countMin = countMin;
        this.density = density;
        this.inN = inN;
        this.inE = inE;
        this.inS = inS;
        this.inW = inW;
        this.outN = outN;
        this.outE = outE;
        this.outS = outS;
        this.outW = outW;
        this.dwellSumSec = dwellSumSec;
        this.dwellCount = dwellCount;
        this.sampleSec = sampleSec;
    }
}
