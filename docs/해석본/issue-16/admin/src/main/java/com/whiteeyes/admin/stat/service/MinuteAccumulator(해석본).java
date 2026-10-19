/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/MinuteAccumulator.java
 * 관련 이슈: #16(FNC-A-024), 이 값을 꺼내 쓰는 쪽은 #18(FNC-A-028 1분 통계 저장)
 *
 * [무엇] '구역 하나 × 1분' 동안 받은 1초 결과들을 담는 바구니. 1분이 끝나면 #18이 꺼내 zone_stat_minute 1행을 만든다.
 * [핵심 설계: 합계를 미리 더하지 않고 초별 결과를 통째로 보관한다]
 *   명세 문장만 보면 "in/out 합, dwell 합"을 받을 때마다 더해 두면 될 것 같다. 하지만 분석 서버는 전송이 실패하면
 *   같은 초를 다시 보낸다(FNC-S-006). 미리 더해 두면 재전송된 초의 유입·유출이 두 번 더해지고, 이미 더한 값은 되돌릴 수 없다.
 *   그래서 '초 → 그 초의 결과'로 저장해 같은 초가 오면 덮어쓰고, 합계는 꺼낼 때 계산한다(사용자와 합의한 결정 2).
 *   1분에 최대 60개라 꺼낼 때마다 계산해도 비용은 무시할 만하다.
 * [염두에 둔 것: #18과의 약속]
 *   - getter 이름이 zone_stat_minute 칼럼과 짝을 이룬다: getMinute ↔ stat_at, getSampleSec ↔ sample_sec,
 *     getInTotal ↔ in_n~in_w, getDwellSumSec ↔ dwell_sum_sec, getDwellCount ↔ dwell_count.
 *   - 평균·최대·최소·밀집도 계산은 #18의 몫이라 여기엔 원재료(초별 인원 목록)만 내준다. 반올림 규칙 같은 #18의 결정을
 *     여기서 미리 정해 버리지 않으려고.
 */
package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 구역 하나의 1분 동안 받은 1초 결과 모음(FNC-A-024). 1분 통계 저장(FNC-A-028)이 마감 때 꺼내 계산한다.
 *
 * <p>재전송으로 같은 초가 다시 오면 그 초의 결과 전체를 마지막 값으로 덮어써서, 인원뿐 아니라 in/out·dwell도 한 번만 센다.
 * 합계는 덮어쓴 뒤의 초별 결과로 꺼낼 때 계산한다.
 *
 * <p>잠금이 없으므로 값은 {@link MinuteAccumulatorStore#closeMinute}로 꺼낸 뒤(더 이상 결과가 들어오지 않을 때)에만 읽는다.
 */
// [해석] 위 마지막 문단이 동시성에 대한 약속이다. 이 클래스 자체는 잠금이 없다. 넣는 동작(put)은 저장소가 잠금을 잡고
//        호출하고, 읽는 동작은 저장소에서 '꺼낸 뒤'에만 하게 해서, 넣는 중에 읽는 상황 자체를 없앴다.
//        잠금을 한 곳(저장소)에만 두면 잠금 순서가 꼬이는 문제(교착)를 걱정할 필요가 없다.
public class MinuteAccumulator {

    // [해석] 어느 구역·어느 분의 바구니인지. 만들어진 뒤 바뀌지 않으므로 final.
    private final long zoneId;
    private final LocalDateTime minute;
    // [해석] TreeMap: 키(초)를 시간 순서로 정렬해 둔다. getPersonCounts가 '초 순서대로' 나오는 이유.
    //        HashMap이면 순서가 뒤죽박죽이라 #18이나 디버깅에서 1초씩 따라가기 어렵다.
    //        put(같은 초, 새 값)은 기존 값을 덮어쓰므로 "같은 at이면 마지막 값만"이 이 자료구조만으로 성립한다.
    private final Map<LocalDateTime, SecondResult> resultsBySecond = new TreeMap<>();

    // [해석] 생성자를 package-private(접근 제어자 없음)으로 둔 이유: 바구니는 저장소(같은 패키지)만 만들 수 있게.
    //        다른 곳에서 new로 따로 만든 바구니는 저장소의 마감 기록과 연결되지 않아 의미가 없다.
    MinuteAccumulator(long zoneId, LocalDateTime minute) {
        this.zoneId = zoneId;
        this.minute = minute;
    }

    public long getZoneId() {
        return zoneId;
    }

    /** 1분 구간 시작 시각(KST, 초는 0). zone_stat_minute.stat_at에 대응한다. */
    public LocalDateTime getMinute() {
        return minute;
    }

    /** 초 순서대로 정렬된 초별 인원 목록. */
    // [해석] #18이 count_avg(평균), count_max/min(최대·최소)을 이 목록으로 계산한다.
    //        toList()는 읽기 전용 리스트를 돌려주므로 #18이 실수로 바꿔도 원본은 안전하다.
    public List<Integer> getPersonCounts() {
        return resultsBySecond.values().stream().map(SecondResult::personCount).toList();
    }

    // [해석] 방향별 유입 합. ZERO에서 시작해 초마다 plus. 결과가 하나도 없으면 ZERO(0,0,0,0)가 나온다.
    public DirectionCounts getInTotal() {
        return resultsBySecond.values().stream()
                .map(SecondResult::in)
                .reduce(DirectionCounts.ZERO, DirectionCounts::plus);
    }

    // [해석] 방향별 유출 합. 유입과 같은 방식.
    public DirectionCounts getOutTotal() {
        return resultsBySecond.values().stream()
                .map(SecondResult::out)
                .reduce(DirectionCounts.ZERO, DirectionCounts::plus);
    }

    // [해석] 구역을 떠난 사람들의 체류 시간 합(초). 1초 결과마다 '그 1초에 떠난 사람들의 체류 시간 목록'이 오므로,
    //        flatMap으로 모든 목록을 한 줄로 펼친 뒤 더한다.
    //        tables.md가 평균이 아니라 '합'과 '개수'를 따로 저장하라고 한 이유: 시간별·일별로 묶을 때
    //        평균의 평균은 틀리지만 합÷개수는 정확하기 때문. 그래서 여기서도 합과 개수를 따로 내준다.
    //        double로 돌려주고 INT 칼럼에 맞춘 반올림은 #18이 정한다.
    public double getDwellSumSec() {
        return resultsBySecond.values().stream()
                .flatMap(result -> result.dwellSec().stream())
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    // [해석] 체류 시간을 잰 사람 수 = 모든 목록의 길이 합. 평균 체류 시간의 분모.
    public int getDwellCount() {
        return resultsBySecond.values().stream()
                .mapToInt(result -> result.dwellSec().size())
                .sum();
    }

    /** 결과가 실제로 들어온 초 수(0~60). zone_stat_minute.sample_sec에 대응한다. */
    // [해석] 맵의 크기 = 서로 다른 초의 개수. 같은 초가 두 번 와도 키가 하나라 1로 센다(검수 기준 "한 번만 반영").
    //        캠이 중간에 끊긴 분은 60보다 작아져 그래프에서 빈 구간을 구분할 수 있다(tables.md 비고).
    public int getSampleSec() {
        return resultsBySecond.size();
    }

    // [해석] package-private: 저장소만 넣을 수 있다. 같은 초면 덮어쓴다(TreeMap.put의 기본 동작).
    void put(LocalDateTime second, SecondResult result) {
        resultsBySecond.put(second, result);
    }
}
