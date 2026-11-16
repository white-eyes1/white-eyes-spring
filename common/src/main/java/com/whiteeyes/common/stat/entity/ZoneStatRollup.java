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
// 별도 테이블이 아니라 자식(시간별·일별) 테이블이 이 칼럼들을 그대로 물려받는다
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ZoneStatRollup {

    // Zone 엔티티가 정식으로 생기기 전이라 id 값으로 둔다
    @Id
    @Column(name = "zone_id")
    private Long zoneId;

    // 그 구간의 평균 인원(하위 통계를 sample_sec로 가중 평균)
    @Column(name = "count_avg", nullable = false, precision = 7, scale = 2)
    private BigDecimal countAvg;

    // 구간 중 최대 인원(하위 통계의 최댓값)
    @Column(name = "count_max", nullable = false)
    private int countMax;

    // 구간 중 최소 인원(하위 통계의 최솟값)
    @Column(name = "count_min", nullable = false)
    private int countMin;

    // 평균 밀집도(명/㎡, sample_sec 가중 평균)
    @Column(name = "density", nullable = false, precision = 8, scale = 4)
    private BigDecimal density;

    // 방향별 유입 인원 합: 북(N)·동(E)·남(S)·서(W)
    @Column(name = "in_n", nullable = false)
    private int inN;

    @Column(name = "in_e", nullable = false)
    private int inE;

    @Column(name = "in_s", nullable = false)
    private int inS;

    @Column(name = "in_w", nullable = false)
    private int inW;

    // 방향별 유출 인원 합: 북(N)·동(E)·남(S)·서(W)
    @Column(name = "out_n", nullable = false)
    private int outN;

    @Column(name = "out_e", nullable = false)
    private int outE;

    @Column(name = "out_s", nullable = false)
    private int outS;

    @Column(name = "out_w", nullable = false)
    private int outW;

    // 체류 시간 합(초). 하루 단위로 합치면 int를 넘을 수 있어 long이다
    @Column(name = "dwell_sum_sec", nullable = false)
    private long dwellSumSec;

    // 체류 시간을 잰 사람 수
    @Column(name = "dwell_count", nullable = false)
    private int dwellCount;

    // 구간 안에서 분석 결과가 들어온 초의 합. 가중 평균의 가중치로 쓴다
    @Column(name = "sample_sec", nullable = false)
    private int sampleSec;

    /** 시연용 데이터 표시(demo-002). 관리자 앱이 묶어 만든 행은 시간별은 0, 일별은 시간별에서 이어받는다. */
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    // 값 묶음(StatValues)을 칼럼으로 풀어 넣는다. 자식 클래스 생성자에서만 호출된다
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
        // 엔티티의 칼럼 값을 다시 StatValues로 묶는다(시간별 → 일별처럼 한 단계 위로 묶을 때 계산기에 넘기려고)
        return new StatValues(
                countAvg,
                countMax,
                countMin,
                density,
                inN,
                inE,
                inS,
                inW,
                outN,
                outE,
                outS,
                outW,
                dwellSumSec,
                dwellCount,
                sampleSec);
    }
}
