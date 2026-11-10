package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 구역 하나의 1분 동안 받은 1초 결과 모음(FNC-A-024). 1분 통계 저장(FNC-A-028)이 마감 때 꺼내 계산한다.
 *
 * <p>재전송으로 같은 초가 다시 오면 그 초의 결과 전체를 마지막 값으로 덮어써서, 인원뿐 아니라 in/out·dwell도 한 번만 센다.
 * 합계는 덮어쓴 뒤의 초별 결과로 꺼낼 때 계산한다.
 *
 * <p>잠금이 없으므로 값은 {@link MinuteAccumulatorStore#closeMinute}로 꺼낸 뒤(더 이상 결과가 들어오지 않을 때)에만 읽는다.
 */
public class MinuteAccumulator {

    private final long zoneId;
    private final LocalDateTime minute;
    private final TreeMap<LocalDateTime, SecondResult> resultsBySecond = new TreeMap<>();

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
    public List<Integer> getPersonCounts() {
        return resultsBySecond.values().stream().map(SecondResult::personCount).toList();
    }

    public DirectionCounts getInTotal() {
        return resultsBySecond.values().stream()
                .map(SecondResult::in)
                .reduce(DirectionCounts.ZERO, DirectionCounts::plus);
    }

    public DirectionCounts getOutTotal() {
        return resultsBySecond.values().stream()
                .map(SecondResult::out)
                .reduce(DirectionCounts.ZERO, DirectionCounts::plus);
    }

    public double getDwellSumSec() {
        return resultsBySecond.values().stream()
                .flatMap(result -> result.dwellSec().stream())
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    public int getDwellCount() {
        return resultsBySecond.values().stream()
                .mapToInt(result -> result.dwellSec().size())
                .sum();
    }

    /** 결과가 실제로 들어온 초 수(0~60). zone_stat_minute.sample_sec에 대응한다. */
    public int getSampleSec() {
        return resultsBySecond.size();
    }

    /** 가장 최근 초의 결과. 현재 인원 저장(FNC-A-054)이 읽는다. 호출은 {@link MinuteAccumulatorStore}의 잠금 안에서만 한다. */
    Optional<Map.Entry<LocalDateTime, SecondResult>> latestSecond() {
        return Optional.ofNullable(resultsBySecond.lastEntry());
    }

    void put(LocalDateTime second, SecondResult result) {
        resultsBySecond.put(second, result);
    }
}
