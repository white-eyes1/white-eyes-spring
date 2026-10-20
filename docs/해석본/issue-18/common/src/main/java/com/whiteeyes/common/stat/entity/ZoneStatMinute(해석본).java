/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/stat/entity/ZoneStatMinute.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] 구역별 1분 통계 한 행(tables.md 'zone_stat_minute'). "구역 3의 14:03 한 분 동안 평균 22.47명…" 같은 값.
 * [누가 쓰나] 저장: 관리자 앱의 1분 마감(#18). 읽기: 지도 색(#20), 현재 현황(#21), 실시간 갱신(FNC-C-022),
 *   시간별 집계(FNC-A-029) 등. 두 앱이 함께 읽는 테이블이라 common 모듈의 stat 패키지에 둔다(package-structure.md).
 * [염두]
 *   - 칼럼 이름·타입·자릿수를 tables.md와 하나도 다르지 않게 맞췄다. 나중에 Flyway V파일이 생기고 ddl-auto가
 *     validate로 돌아가면, 엔티티와 실제 테이블이 다를 때 앱이 뜨지 않기 때문.
 *   - 평균 체류 시간이 아니라 '합(dwell_sum_sec)과 개수(dwell_count)'를 저장한다. 평균끼리 평균을 내면 틀리지만,
 *     합과 개수는 시간별·일별로 더한 뒤 나누면 정확한 평균이 나온다(requirements.md vanly-004).
 *   - setter가 없다. 한 번 계산해 저장하는 값이라 바뀔 일이 없고, 덮어쓰기는 리포지토리의 upsert가 한다.
 */
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
// [해석] 복합 키 (zone_id, stat_at)를 ZoneStatMinuteId 클래스로 표현한다. 아래 @Id가 두 개인 이유.
@IdClass(ZoneStatMinuteId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneStatMinute {

    // Zone 엔티티가 정식으로 생기기 전이라 id 값으로 둔다
    // [해석] 원래라면 @ManyToOne으로 Zone 객체를 가리킬 수도 있지만, Zone이 임시 코드라 #16의 Camera.zoneId처럼
    //        숫자 id만 둔다. 통계를 저장·조회할 때 구역 객체 전체가 필요하지도 않다.
    @Id
    @Column(name = "zone_id")
    private Long zoneId;

    /** 1분 구간 시작 시각(KST, 초는 0). */
    // [해석] 14:03:00~14:03:59의 통계면 14:03:00. 시각은 KST LocalDateTime으로 저장한다(code-conventions, tables.md).
    @Id
    @Column(name = "stat_at")
    private LocalDateTime statAt;

    // [해석] 1분 평균 인원. DECIMAL(7,2) → 소수 2자리. 계산과 반올림은 MinuteStatCalculator가 한다.
    @Column(name = "count_avg", nullable = false, precision = 7, scale = 2)
    private BigDecimal countAvg;

    @Column(name = "count_max", nullable = false)
    private int countMax;

    @Column(name = "count_min", nullable = false)
    private int countMin;

    // [해석] 평균 밀집도(명/㎡). DECIMAL(8,4). 면적이 나중에 바뀌어도 '그때의 밀집도'가 남도록 계산값 자체를 저장한다(tables.md).
    @Column(name = "density", nullable = false, precision = 8, scale = 4)
    private BigDecimal density;

    // [해석] 방향별(북·동·남·서) 유입 인원 합. 이웃 구역의 유출과 짝지어 구역 간 이동을 계산하는 데 쓰인다.
    @Column(name = "in_n", nullable = false)
    private int inN;

    @Column(name = "in_e", nullable = false)
    private int inE;

    @Column(name = "in_s", nullable = false)
    private int inS;

    @Column(name = "in_w", nullable = false)
    private int inW;

    // [해석] 방향별 유출 인원 합.
    @Column(name = "out_n", nullable = false)
    private int outN;

    @Column(name = "out_e", nullable = false)
    private int outE;

    @Column(name = "out_s", nullable = false)
    private int outS;

    @Column(name = "out_w", nullable = false)
    private int outW;

    // [해석] 구역을 떠난 사람들의 체류 시간 합(초)과 그 인원. 평균 체류 시간 = 합 ÷ 개수(조회할 때 계산).
    @Column(name = "dwell_sum_sec", nullable = false)
    private int dwellSumSec;

    @Column(name = "dwell_count", nullable = false)
    private int dwellCount;

    // [해석] 결과가 실제로 들어온 초 수(0~60). 캠이 끊긴 구간을 구분하고, 시간별로 묶을 때 평균의 가중치가 된다.
    //        tables.md 타입이 SMALLINT라 Java도 short로 맞췄다(int로 두면 나중에 validate가 타입이 다르다고 거부함).
    @Column(name = "sample_sec", nullable = false)
    private short sampleSec;

    // [해석] @Builder: 칼럼이 17개라 생성자에 순서대로 넣으면 in_n과 in_e를 바꿔 넣는 실수를 해도 컴파일러가 못 잡는다.
    //        빌더는 .inN(6).inE(2)처럼 이름을 붙여 넣어서 이런 실수를 막는다.
    //        생성자는 private이라 빌더로만 만들 수 있다.
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
