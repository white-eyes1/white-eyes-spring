package com.whiteeyes.admin.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

import com.whiteeyes.admin.demo.repository.DemoHourlyStat;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.Season;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.SurgeDay;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.stat.entity.StatValues;

/**
 * 규칙으로 시연용 시간별 통계를 만든다(FNC-A-051 처리 순서 2~4). DB를 보지 않는 계산만 한다.
 *
 * <p>인원 = 기본 인원 × 시간대 × 요일 × 계절 × 특정일 가중치 × 무작위(±10%).
 * 구역을 떠나는 인원은 평균 인원 × 3600 ÷ 평균 체류(시간당 드나드는 인원)를 네 방향에 나눈 값이다.
 * 이웃 쌍은 A의 d 방향 유출을 B의 반대 방향 유입으로 그대로 쓰고, 이웃이 없는 방향은 바깥에서 들어오는 인원을 따로 만든다.
 *
 * <p>무작위 값은 구역마다 seed와 구역 번호로 정한 난수열에서 날짜·시각 순서대로 꺼낸다.
 * 그래서 같은 seed·같은 구역이면 결과가 같고, 이웃 유무와 상관없이 시간마다 같은 개수를 꺼낸다.
 */
final class DemoStatGenerator {

    static final int SAMPLE_SEC = 3600;

    private static final int SECONDS_PER_HOUR = 3600;
    private static final double COUNT_NOISE = 0.10;
    // 명세에 이동량의 변동 폭이 없어 인원과 같은 ±10%로 둔다
    private static final double FLOW_NOISE = 0.10;
    private static final double DWELL_NOISE = 0.20;
    private static final BigDecimal COUNT_MAX_RATIO = new BigDecimal("1.3");
    private static final BigDecimal COUNT_MIN_RATIO = new BigDecimal("0.7");
    private static final ZoneDirection[] DIRECTIONS = ZoneDirection.values();
    private static final long ZONE_SEED_MULTIPLIER = 31;

    private final DemoGenerateConfig config;
    private final List<DemoZone> zones;
    private final Map<Long, SplittableRandom> randoms = new HashMap<>();
    private final Map<LocalDate, Double> surgeWeights;

    DemoStatGenerator(DemoGenerateConfig config, List<DemoZone> zones) {
        this.config = config;
        this.zones = zones.stream().sorted(Comparator.comparingLong(DemoZone::id)).toList();
        for (DemoZone zone : this.zones) {
            randoms.put(zone.id(), new SplittableRandom(config.seed() * ZONE_SEED_MULTIPLIER + zone.id()));
        }
        this.surgeWeights = config.surgeDays() == null
                ? Map.of()
                : config.surgeDays().stream().collect(Collectors.toMap(SurgeDay::date, SurgeDay::weight));
    }

    /**
     * 그날 데이터를 만들 모든 구역의 0~23시 통계를 만든다. 날짜는 앞에서부터 하루씩 차례로 불러야 같은 seed에서 같은 결과가 나온다.
     *
     * @return 구역 번호·시각 순. 그날 만들 구역이 없으면 빈 목록
     */
    List<DemoHourlyStat> generateDay(LocalDate date) {
        List<DemoZone> activeZones = zones.stream()
                .filter(zone -> zone.covers(config.startDate(), date))
                .toList();
        Map<Long, DrawnHour[]> drawn = new HashMap<>();
        for (DemoZone zone : activeZones) {
            drawn.put(zone.id(), drawDay(zone, date));
        }

        List<DemoHourlyStat> result = new ArrayList<>();
        for (int hour = 0; hour < DemoGenerateConfig.HOURS_PER_DAY; hour++) {
            Map<Long, int[]> pairedInflows = pairInflows(activeZones, drawn, hour);
            for (DemoZone zone : activeZones) {
                DrawnHour drawnHour = drawn.get(zone.id())[hour];
                StatValues values = toValues(zone, drawnHour, pairedInflows.get(zone.id()));
                result.add(new DemoHourlyStat(zone.id(), date.atTime(hour, 0), values));
            }
        }
        result.sort(Comparator.comparingLong(DemoHourlyStat::zoneId).thenComparing(DemoHourlyStat::statAt));
        return result;
    }

    private DrawnHour[] drawDay(DemoZone zone, LocalDate date) {
        SplittableRandom random = randoms.get(zone.id());
        double dayWeight = config.dayOfWeekWeights().get(date.getDayOfWeek())
                * config.seasonWeights().get(Season.of(date.getMonth()))
                * surgeWeights.getOrDefault(date, 1.0);
        DrawnHour[] hours = new DrawnHour[DemoGenerateConfig.HOURS_PER_DAY];
        for (int hour = 0; hour < hours.length; hour++) {
            hours[hour] = drawHour(zone, dayWeight * config.hourWeights().get(hour), random);
        }
        return hours;
    }

    // 꺼내는 순서: 인원 1개 → 방향별 유출 4개 → 방향별 바깥 유입 4개 → 체류 1개
    private static DrawnHour drawHour(DemoZone zone, double weight, SplittableRandom random) {
        BigDecimal countAvg = BigDecimal.valueOf(zone.baseCount() * weight * noise(random, COUNT_NOISE))
                .setScale(2, RoundingMode.HALF_UP);
        double perDirection = countAvg.doubleValue() * SECONDS_PER_HOUR / zone.avgDwellSec() / DIRECTIONS.length;
        int[] outflows = new int[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            outflows[i] = Math.toIntExact(Math.round(perDirection * noise(random, FLOW_NOISE)));
        }
        int[] outsideInflows = new int[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            outsideInflows[i] = Math.toIntExact(Math.round(perDirection * noise(random, FLOW_NOISE)));
        }
        return new DrawnHour(countAvg, outflows, outsideInflows, noise(random, DWELL_NOISE));
    }

    /**
     * 이웃 쌍의 유입을 맞춘다(FNC-A-051 처리 순서 3): A의 d 방향 이웃이 B면 A의 d 유출이 B의 반대 방향 유입이다.
     * 이웃 구역이 그날 데이터를 만들지 않으면(캠 가동 뒤 등) 짝을 짓지 않는다.
     *
     * @return 구역 번호 → 방향별 유입. 짝지어진 방향이 없으면 그 칸은 null
     */
    private static Map<Long, int[]> pairInflows(List<DemoZone> activeZones, Map<Long, DrawnHour[]> drawn, int hour) {
        Map<Long, int[]> inflows = new HashMap<>();
        for (DemoZone from : activeZones) {
            int[] outflows = drawn.get(from.id())[hour].outflows();
            from.neighborIds().forEach((direction, toId) -> {
                if (!drawn.containsKey(toId)) {
                    return;
                }
                int[] target = inflows.computeIfAbsent(toId, id -> emptyInflows());
                int side = direction.opposite().ordinal();
                target[side] = Math.max(target[side], 0) + outflows[direction.ordinal()];
            });
        }
        return inflows;
    }

    private static StatValues toValues(DemoZone zone, DrawnHour drawn, int[] pairedInflows) {
        int[] inflows = new int[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            boolean paired = pairedInflows != null && pairedInflows[i] >= 0;
            inflows[i] = paired ? pairedInflows[i] : drawn.outsideInflows()[i];
        }
        int[] outflows = drawn.outflows();
        int dwellCount = 0;
        for (int outflow : outflows) {
            dwellCount += outflow;
        }
        long dwellSumSec = Math.round(dwellCount * (double) zone.avgDwellSec() * drawn.dwellFactor());
        BigDecimal countAvg = drawn.countAvg();

        // count_max = avg×1.3, count_min = avg×0.7, density = avg÷면적, sample_sec = 3600(FNC-A-051 처리 순서 4)
        return new StatValues(
                countAvg,
                countAvg.multiply(COUNT_MAX_RATIO).setScale(0, RoundingMode.HALF_UP).intValueExact(),
                countAvg.multiply(COUNT_MIN_RATIO).setScale(0, RoundingMode.HALF_UP).intValueExact(),
                countAvg.divide(zone.areaM2(), 4, RoundingMode.HALF_UP),
                inflows[ZoneDirection.N.ordinal()],
                inflows[ZoneDirection.E.ordinal()],
                inflows[ZoneDirection.S.ordinal()],
                inflows[ZoneDirection.W.ordinal()],
                outflows[ZoneDirection.N.ordinal()],
                outflows[ZoneDirection.E.ordinal()],
                outflows[ZoneDirection.S.ordinal()],
                outflows[ZoneDirection.W.ordinal()],
                dwellSumSec,
                dwellCount,
                SAMPLE_SEC);
    }

    // 짝지어진 유입이 없는 칸을 -1로 표시한다(0명 유입과 구분)
    private static int[] emptyInflows() {
        int[] inflows = new int[DIRECTIONS.length];
        Arrays.fill(inflows, -1);
        return inflows;
    }

    // 1 ± ratio 사이의 배율
    private static double noise(SplittableRandom random, double ratio) {
        return 1 + (random.nextDouble() * 2 - 1) * ratio;
    }

    /** 한 구역·한 시각에 꺼낸 무작위 값으로 정한 인원·유출·바깥 유입·체류 배율. */
    private record DrawnHour(BigDecimal countAvg, int[] outflows, int[] outsideInflows, double dwellFactor) {
    }
}
