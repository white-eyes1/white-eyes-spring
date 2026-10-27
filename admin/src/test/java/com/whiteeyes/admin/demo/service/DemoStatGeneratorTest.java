package com.whiteeyes.admin.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.admin.demo.repository.DemoHourlyStat;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.Season;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.SurgeDay;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.stat.entity.StatValues;

/** 시연용 통계 생성(FNC-A-051)의 규칙 계산 */
class DemoStatGeneratorTest {

    // 2026-01-05는 월요일(겨울)
    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);
    private static final LocalDate END = MONDAY.plusDays(6);

    private static final LocalDateTime START_AT = MONDAY.atStartOfDay();
    private static final LocalDateTime END_AT = END.plusDays(1).atStartOfDay();

    // 1번 구역의 동쪽 이웃이 2번(2번의 서쪽 이웃이 1번)
    private static final DemoZone WEST = new DemoZone(
            1L, new BigDecimal("100.00"), 40, 600, START_AT, END_AT, Map.of(ZoneDirection.E, 2L));
    private static final DemoZone EAST = new DemoZone(
            2L, new BigDecimal("50.00"), 20, 300, START_AT, END_AT, Map.of(ZoneDirection.W, 1L));

    @Test
    @DisplayName("FNC-A-051 같은 seed면 같은 결과, 다른 seed면 다른 결과")
    void sameSeedSameResult() {
        List<DemoHourlyStat> first = generateWeek(config(7L, flatHours(), List.of()));
        List<DemoHourlyStat> second = generateWeek(config(7L, flatHours(), List.of()));
        List<DemoHourlyStat> other = generateWeek(config(8L, flatHours(), List.of()));

        assertThat(first).hasSize(2 * 7 * 24).isEqualTo(second);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    @DisplayName("FNC-A-051 이웃 쌍은 A의 d 방향 유출 = B의 반대 방향 유입, 이웃 없는 방향은 바깥과 따로 주고받는다")
    void neighborFlowsArePaired() {
        List<DemoHourlyStat> rows = new DemoStatGenerator(config(7L, flatHours(), List.of()), List.of(WEST, EAST))
                .generateDay(MONDAY);
        Map<String, StatValues> byKey = rows.stream()
                .collect(Collectors.toMap(row -> row.zoneId() + "@" + row.statAt().getHour(), DemoHourlyStat::values));

        for (int hour = 0; hour < 24; hour++) {
            StatValues west = byKey.get("1@" + hour);
            StatValues east = byKey.get("2@" + hour);
            assertThat(east.inW()).isEqualTo(west.outE());
            assertThat(west.inE()).isEqualTo(east.outW());
            // 바깥 방향도 사람이 오간다(평균 인원 40·체류 600초 → 방향마다 시간당 약 60명)
            assertThat(west.inN()).isPositive();
            assertThat(west.outN()).isPositive();
        }
    }

    @Test
    @DisplayName("FNC-A-051 이웃 구역이 그날 데이터를 만들지 않으면 그 방향 유입은 바깥 유입으로 만든다")
    void neighborWithoutDataIsTreatedAsOutside() {
        DemoZone eastEndedEarlier = new DemoZone(
                2L, new BigDecimal("50.00"), 20, 300, START_AT.minusDays(7), START_AT, Map.of(ZoneDirection.W, 1L));
        List<DemoHourlyStat> rows = new DemoStatGenerator(
                        config(7L, flatHours(), List.of()), List.of(WEST, eastEndedEarlier))
                .generateDay(MONDAY);

        assertThat(rows).extracting(DemoHourlyStat::zoneId).containsOnly(1L);
        assertThat(rows).allSatisfy(row -> assertThat(row.values().inE()).isPositive());
    }

    @Test
    @DisplayName("FNC-A-051 count_max = avg×1.3, count_min = avg×0.7, density = avg÷면적, dwell_count = 유출 합, sample_sec = 3600")
    void derivedColumns() {
        List<DemoHourlyStat> rows = generateWeek(config(7L, flatHours(), List.of()));

        assertThat(rows).allSatisfy(row -> {
            StatValues values = row.values();
            DemoZone zone = row.zoneId() == 1L ? WEST : EAST;
            assertThat(values.countMax()).isEqualTo(round(values.countAvg().multiply(new BigDecimal("1.3"))));
            assertThat(values.countMin()).isEqualTo(round(values.countAvg().multiply(new BigDecimal("0.7"))));
            assertThat(values.density())
                    .isEqualByComparingTo(values.countAvg().divide(zone.areaM2(), 4, RoundingMode.HALF_UP));
            assertThat(values.dwellCount())
                    .isEqualTo(values.outN() + values.outE() + values.outS() + values.outW());
            // 체류 합 = 유출 합 × 평균 체류(±20%)
            assertThat((double) values.dwellSumSec())
                    .isBetween(values.dwellCount() * zone.avgDwellSec() * 0.8, values.dwellCount() * zone.avgDwellSec() * 1.2);
            assertThat(values.sampleSec()).isEqualTo(3600);
        });
    }

    @Test
    @DisplayName("FNC-A-051 인원 = 기본 인원 × 시간대·요일·계절·특정일 가중치 × 무작위(±10%)")
    void countFollowsWeights() {
        List<Double> hourWeights = flatHours();
        hourWeights.set(3, 0.0);
        hourWeights.set(18, 2.0);
        LocalDate surge = MONDAY.plusDays(2);
        Map<String, BigDecimal> avg = generateWeek(config(7L, hourWeights, List.of(new SurgeDay(surge, 3.0))))
                .stream()
                .filter(row -> row.zoneId() == 1L)
                .collect(Collectors.toMap(row -> row.statAt().toString(), row -> row.values().countAvg()));

        // 기본 40 × 겨울 0.5 = 20, 평일 1, 주말 1.5
        assertThat(avg.get(MONDAY.atTime(3, 0).toString())).isEqualByComparingTo("0");
        assertThat(avg.get(MONDAY.atTime(10, 0).toString()).doubleValue()).isBetween(18.0, 22.0);
        assertThat(avg.get(MONDAY.atTime(18, 0).toString()).doubleValue()).isBetween(36.0, 44.0);
        assertThat(avg.get(MONDAY.plusDays(5).atTime(10, 0).toString()).doubleValue()).isBetween(27.0, 33.0);
        assertThat(avg.get(surge.atTime(10, 0).toString()).doubleValue()).isBetween(54.0, 66.0);
    }

    @Test
    @DisplayName("FNC-A-051 시작 시각 전과 끝 시각부터는 만들지 않는다(끝 시각이 13시면 그날 0~12시만)")
    void onlyBetweenStartAndEnd() {
        DemoZone endsAt13 = new DemoZone(
                3L, new BigDecimal("10.00"), 10, 600, START_AT, END.atTime(13, 0), Map.of());
        DemoStatGenerator generator =
                new DemoStatGenerator(config(7L, flatHours(), List.of()), List.of(WEST, EAST, endsAt13));

        assertThat(generator.generateDay(MONDAY.minusDays(1))).isEmpty();
        assertThat(generator.generateDay(END.plusDays(1))).isEmpty();
        assertThat(generator.generateDay(END))
                .filteredOn(row -> row.zoneId() == 3L)
                .extracting(row -> row.statAt().getHour())
                .containsExactlyElementsOf(IntStream.range(0, 13).boxed().toList());
    }

    @Test
    @DisplayName("FNC-A-051 난수는 (seed, 구역, 절대 시각)으로 정해져 시작일이나 만드는 순서와 상관없이 같은 시각의 값이 같다")
    void sameValuesRegardlessOfStartAndOrder() {
        DemoGenerateConfig config = config(7L, flatHours(), List.of());
        DemoZone westFromEarlier = new DemoZone(
                1L, WEST.areaM2(), WEST.baseCount(), WEST.avgDwellSec(), START_AT.minusDays(30), END_AT,
                WEST.neighborIds());
        DemoZone eastFromEarlier = new DemoZone(
                2L, EAST.areaM2(), EAST.baseCount(), EAST.avgDwellSec(), START_AT.minusDays(30), END_AT,
                EAST.neighborIds());
        DemoStatGenerator later = new DemoStatGenerator(config, List.of(WEST, EAST));
        DemoStatGenerator earlier = new DemoStatGenerator(config, List.of(westFromEarlier, eastFromEarlier));

        List<DemoHourlyStat> laterWednesday = later.generateDay(MONDAY.plusDays(2));
        earlier.generateDay(MONDAY.minusDays(10));

        assertThat(earlier.generateDay(MONDAY.plusDays(2))).isEqualTo(laterWednesday);
    }

    @Test
    @DisplayName("FNC-A-051 일부 구역만 만들어도 만들지 않는 이웃의 유출을 같은 규칙으로 계산해 유입을 맞추고, 이웃 행은 돌려주지 않는다")
    void pairsWithNeighborOutsideTargets() {
        DemoGenerateConfig config = config(7L, flatHours(), List.of());
        List<DemoHourlyStat> both = new DemoStatGenerator(config, List.of(WEST, EAST)).generateDay(MONDAY);
        List<DemoHourlyStat> westOnly =
                new DemoStatGenerator(config, List.of(WEST, EAST), Set.of(1L)).generateDay(MONDAY);

        assertThat(westOnly).extracting(DemoHourlyStat::zoneId).containsOnly(1L);
        assertThat(westOnly).isEqualTo(both.stream().filter(row -> row.zoneId() == 1L).toList());
    }

    private static List<DemoHourlyStat> generateWeek(DemoGenerateConfig config) {
        DemoStatGenerator generator = new DemoStatGenerator(config, List.of(WEST, EAST));
        return MONDAY.datesUntil(END.plusDays(1))
                .flatMap(date -> generator.generateDay(date).stream())
                .toList();
    }

    private static DemoGenerateConfig config(long seed, List<Double> hourWeights, List<SurgeDay> surgeDays) {
        Map<DayOfWeek, Double> dayOfWeek = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            dayOfWeek.put(day, day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? 1.5 : 1.0);
        }
        Map<Season, Double> season = new EnumMap<>(Season.class);
        for (Season value : Season.values()) {
            season.put(value, value == Season.WINTER ? 0.5 : 1.0);
        }
        DemoGenerateConfig config =
                new DemoGenerateConfig(seed, new DemoGenerateConfig.ZoneSetting(null, 1.0, 600), List.of(), hourWeights,
                        dayOfWeek, season, surgeDays);
        config.validate();
        return config;
    }

    private static List<Double> flatHours() {
        return new ArrayList<>(Collections.nCopies(24, 1.0));
    }

    private static int round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
