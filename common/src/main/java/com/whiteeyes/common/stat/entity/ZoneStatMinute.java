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
// 1분 통계 테이블과 매핑되는 엔티티. 키는 (zone_id, stat_at) 복합 키라 @IdClass를 쓴다
// 시간별·일별과 달리 부모 클래스를 쓰지 않고(dwell_sum_sec가 int, sample_sec가 short) 칼럼을 직접 가진다
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

    // 그 1분의 평균 인원(소수 둘째 자리까지)
    @Column(name = "count_avg", nullable = false, precision = 7, scale = 2)
    private BigDecimal countAvg;

    // 그 1분 중 최대 인원
    @Column(name = "count_max", nullable = false)
    private int countMax;

    // 그 1분 중 최소 인원
    @Column(name = "count_min", nullable = false)
    private int countMin;

    // 평균 밀집도(명/㎡)
    @Column(name = "density", nullable = false, precision = 8, scale = 4)
    private BigDecimal density;

    // 방향별 유입 인원: 북(N)·동(E)·남(S)·서(W)
    @Column(name = "in_n", nullable = false)
    private int inN;

    @Column(name = "in_e", nullable = false)
    private int inE;

    @Column(name = "in_s", nullable = false)
    private int inS;

    @Column(name = "in_w", nullable = false)
    private int inW;

    // 방향별 유출 인원: 북(N)·동(E)·남(S)·서(W)
    @Column(name = "out_n", nullable = false)
    private int outN;

    @Column(name = "out_e", nullable = false)
    private int outE;

    @Column(name = "out_s", nullable = false)
    private int outS;

    @Column(name = "out_w", nullable = false)
    private int outW;

    // 체류 시간 합(초). 평균 체류 시간은 dwell_sum_sec / dwell_count로 구한다
    @Column(name = "dwell_sum_sec", nullable = false)
    private int dwellSumSec;

    // 체류 시간을 잰 사람 수
    @Column(name = "dwell_count", nullable = false)
    private int dwellCount;

    // 그 1분 중 분석 결과가 들어온 초(0~60). 0이면 통계가 없는 분으로 다룬다
    @Column(name = "sample_sec", nullable = false)
    private short sampleSec;

    // 생성자는 외부에서 쓰지 않고 빌더로만 만들게 private으로 두었다(필드가 많아 순서 혼동을 막는다)
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
