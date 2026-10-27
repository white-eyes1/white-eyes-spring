package com.whiteeyes.admin.demo.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;

/**
 * 시연용 통계 생성 설정(FNC-A-051 입력 항목). 기본값은 {@link #defaults()}이고, 설정 파일(--config, 선택)에 적은 항목만 덮어쓴다.
 * 설정: seed, 구역별 기본 인원·평균 체류(초), 패턴 가중치(시간대·요일·계절), 특정일 급증 날짜 목록.
 * 시작일은 설정하지 않는다. 구역마다 끝 시각의 1년 전 날짜 0시부터 만든다.
 *
 * @param seed 무작위 변동의 시작값. 같은 seed면 같은 결과
 * @param defaultZone {@code zones}에 없는 구역에 쓸 값
 * @param zones 구역별 기본 인원·평균 체류
 * @param hourWeights 0~23시 가중치(24개)
 * @param dayOfWeekWeights 요일 가중치(MONDAY~SUNDAY 7개)
 * @param seasonWeights 계절 가중치(SPRING·SUMMER·AUTUMN·WINTER 4개)
 * @param surgeDays 특정일 급증. 없는 날의 가중치는 1
 */
public record DemoGenerateConfig(
        Long seed,
        ZoneSetting defaultZone,
        List<ZoneSetting> zones,
        List<Double> hourWeights,
        Map<DayOfWeek, Double> dayOfWeekWeights,
        Map<Season, Double> seasonWeights,
        List<SurgeDay> surgeDays) {

    static final int HOURS_PER_DAY = 24;

    /**
     * 설정 파일이 없을 때 쓰는 값. 가중치는 출퇴근·점심·저녁과 주말·가을이 붐비는 모양이고, 특정일 급증은 없다.
     * 설정 견본(admin/demo/generate-demo.example.json)도 같은 값을 적어 둔다.
     */
    static DemoGenerateConfig defaults() {
        return new DemoGenerateConfig(
                20261024L,
                new ZoneSetting(null, 30.0, 600),
                List.of(),
                List.of(0.2, 0.1, 0.1, 0.1, 0.1, 0.2, 0.4, 0.9, 1.4, 1.0, 0.8, 1.0,
                        1.4, 1.2, 0.9, 0.9, 1.0, 1.2, 1.6, 1.5, 1.2, 0.9, 0.6, 0.4),
                Map.of(DayOfWeek.MONDAY, 1.0, DayOfWeek.TUESDAY, 1.0, DayOfWeek.WEDNESDAY, 1.0,
                        DayOfWeek.THURSDAY, 1.0, DayOfWeek.FRIDAY, 1.1, DayOfWeek.SATURDAY, 1.4, DayOfWeek.SUNDAY, 1.3),
                Map.of(Season.SPRING, 1.1, Season.SUMMER, 0.9, Season.AUTUMN, 1.2, Season.WINTER, 0.7),
                List.of());
    }

    /**
     * @param zoneId 구역 번호. {@code defaultZone}에서는 비워 둔다
     * @param baseCount 기본 인원(가중치 1일 때의 시간 평균 인원)
     * @param avgDwellSec 평균 체류(초)
     */
    public record ZoneSetting(Long zoneId, Double baseCount, Integer avgDwellSec) {
    }

    /**
     * @param date 급증하는 날짜(KST)
     * @param weight 그날의 가중치
     */
    public record SurgeDay(LocalDate date, Double weight) {
    }

    /** 계절. 3~5월 봄, 6~8월 여름, 9~11월 가을, 12~2월 겨울. */
    public enum Season {
        SPRING,
        SUMMER,
        AUTUMN,
        WINTER;

        static Season of(Month month) {
            return switch (month) {
                case MARCH, APRIL, MAY -> SPRING;
                case JUNE, JULY, AUGUST -> SUMMER;
                case SEPTEMBER, OCTOBER, NOVEMBER -> AUTUMN;
                case DECEMBER, JANUARY, FEBRUARY -> WINTER;
            };
        }
    }

    /**
     * 빠진 값·틀린 값을 찾는다. 기존 시연용 데이터를 지우기 전에 불러서, 설정이 틀리면 아무것도 바꾸지 않고 멈춘다.
     *
     * @throws IllegalArgumentException 설정이 틀렸을 때
     */
    void validate() {
        require(seed != null, "seed가 필요합니다");
        require(defaultZone != null, "defaultZone이 필요합니다");
        require(defaultZone.zoneId() == null, "defaultZone에는 zoneId를 쓰지 않습니다");
        validateZoneValues(defaultZone, "defaultZone");
        if (zones != null) {
            for (ZoneSetting zone : zones) {
                require(zone.zoneId() != null, "zones의 각 항목에 zoneId가 필요합니다");
                validateZoneValues(zone, "zoneId=" + zone.zoneId());
            }
            long distinct = zones.stream().map(ZoneSetting::zoneId).distinct().count();
            require(distinct == zones.size(), "zones에 같은 zoneId가 두 번 있습니다");
        }
        require(hourWeights != null && hourWeights.size() == HOURS_PER_DAY, "hourWeights는 24개(0~23시)여야 합니다");
        hourWeights.forEach(weight -> requireWeight(weight, "hourWeights"));
        require(
                dayOfWeekWeights != null && dayOfWeekWeights.size() == DayOfWeek.values().length,
                "dayOfWeekWeights는 MONDAY~SUNDAY 7개여야 합니다");
        dayOfWeekWeights.values().forEach(weight -> requireWeight(weight, "dayOfWeekWeights"));
        require(
                seasonWeights != null && seasonWeights.size() == Season.values().length,
                "seasonWeights는 SPRING·SUMMER·AUTUMN·WINTER 4개여야 합니다");
        seasonWeights.values().forEach(weight -> requireWeight(weight, "seasonWeights"));
        if (surgeDays != null) {
            for (SurgeDay surgeDay : surgeDays) {
                require(surgeDay.date() != null, "surgeDays의 각 항목에 date가 필요합니다");
                requireWeight(surgeDay.weight(), "surgeDays " + surgeDay.date());
            }
            long distinct = surgeDays.stream().map(SurgeDay::date).distinct().count();
            require(distinct == surgeDays.size(), "surgeDays에 같은 날짜가 두 번 있습니다");
        }
    }

    private static void validateZoneValues(ZoneSetting zone, String name) {
        require(zone.baseCount() != null && zone.baseCount() >= 0, name + "의 baseCount는 0 이상이어야 합니다");
        require(zone.avgDwellSec() != null && zone.avgDwellSec() > 0, name + "의 avgDwellSec는 1 이상이어야 합니다");
    }

    private static void requireWeight(Double weight, String name) {
        require(weight != null && weight >= 0, name + "의 가중치는 0 이상이어야 합니다");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("시연용 통계 설정이 틀렸습니다: " + message);
        }
    }
}
