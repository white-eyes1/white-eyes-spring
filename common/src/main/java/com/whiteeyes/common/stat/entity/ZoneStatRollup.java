package com.whiteeyes.common.stat.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시간별·일별 통계가 함께 쓰는 칼럼(tables.md 'zone_stat_hourly', 'zone_stat_daily').
 * count_avg·density는 하위 통계를 sample_sec로 가중 평균한 값이다.
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ZoneStatRollup {

    // Zone 엔티티가 정식으로 생기기 전이라 id 값으로 둔다
    @Id
    @Column(name = "zone_id")
    private Long zoneId;

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
    private long dwellSumSec;

    @Column(name = "dwell_count", nullable = false)
    private int dwellCount;

    @Column(name = "sample_sec", nullable = false)
    private int sampleSec;

    /** 시연용 데이터 표시(demo-002). 관리자 앱이 묶어 만든 행은 시간별은 0, 일별은 시간별에서 이어받는다. */
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    protected ZoneStatRollup(Long zoneId, StatValues values, boolean demo) {
        this.zoneId = zoneId;
        this.countAvg = values.countAvg();
        this.countMax = values.countMax();
        this.countMin = values.countMin();
        this.density = values.density();
        this.inN = values.inN();
        this.inE = values.inE();
        this.inS = values.inS();
        this.inW = values.inW();
        this.outN = values.outN();
        this.outE = values.outE();
        this.outS = values.outS();
        this.outW = values.outW();
        this.dwellSumSec = values.dwellSumSec();
        this.dwellCount = values.dwellCount();
        this.sampleSec = values.sampleSec();
        this.demo = demo;
    }

    /** 상위 통계로 다시 묶을 때 쓰는 값. */
    public StatValues values() {
        return new StatValues(countAvg, countMax, countMin, density, inN, inE, inS, inW, outN, outE, outS, outW,
                dwellSumSec, dwellCount, sampleSec);
    }
}
