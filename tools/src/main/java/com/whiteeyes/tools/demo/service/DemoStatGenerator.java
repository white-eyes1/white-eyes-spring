package com.whiteeyes.tools.demo.service;

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
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.tools.demo.repository.DemoHourlyStat;
import com.whiteeyes.tools.demo.service.DemoGenerateConfig.Season;
import com.whiteeyes.tools.demo.service.DemoGenerateConfig.SurgeDay;

/**
 * 규칙으로 시연용 시간별 통계를 만든다(FNC-A-051 처리 순서 2~4). DB를 보지 않는 계산만 한다.
 *
 * <p>인원 = 기본 인원 × 시간대 × 요일 × 계절 × 특정일 가중치 × 무작위(±10%).
 * 구역을 떠나는 인원은 평균 인원 × 3600 ÷ 평균 체류(시간당 드나드는 인원)를 네 방향에 나눈 값이다.
 * 이웃 쌍은 A의 d 방향 유출을 B의 반대 방향 유입으로 그대로 쓰고, 이웃이 없는 방향은 바깥에서 들어오는 인원을 따로 만든다.
 *
 * <p>무작위 값은 (seed, 구역, 시각)으로 정한 난수에서 꺼낸다. 그래서 언제 실행하든, 어느 날짜부터 만들든
 * 같은 seed·같은 구역·같은 시각이면 값이 같다. 만들지 않는 이웃 구역(--zone 밖)도 같은 규칙으로 유출을 계산해
 * 유입을 맞추고, 그 이웃의 행은 돌려주지 않는다.
 */
final class DemoStatGenerator {

    // 시간별 행 하나가 대표하는 초(1시간).
    static final int SAMPLE_SEC = 3600;

    private static final int SECONDS_PER_HOUR = 3600;
    // 값에 곱하는 무작위 배율의 폭(1 ± 비율). 인원과 이동량은 ±10%, 체류는 ±20%.
    private static final double COUNT_NOISE = 0.10;
    // 명세에 이동량의 변동 폭이 없어 인원과 같은 ±10%로 둔다
    private static final double FLOW_NOISE = 0.10;
    private static final double DWELL_NOISE = 0.20;
    // 최대·최소 인원은 평균 인원의 1.3배·0.7배로 정한다(FNC-A-051 처리 순서 4).
    private static final BigDecimal COUNT_MAX_RATIO = new BigDecimal("1.3");
    private static final BigDecimal COUNT_MIN_RATIO = new BigDecimal("0.7");
    // 방향 배열의 인덱스는 ZoneDirection 선언 순서와 같다. 유입·유출 배열이 이 순서를 따른다.
    private static final ZoneDirection[] DIRECTIONS = ZoneDirection.values();
    // 난수 씨앗을 만들 때 seed, 구역 번호, 시각 번호가 서로 겹치지 않게 섞는 곱수.
    private static final long ZONE_SEED_MULTIPLIER = 31;
    // 구역마다 시각 번호가 겹치지 않게 벌린다(100년 치 시간 수보다 크다)
    private static final long HOUR_SEED_MULTIPLIER = 1_000_003;

    // config: 설정, zones: 이웃 짝 맞추기에 쓰는 모든 구역, targetIds: 행을 실제로 만들 구역, surgeWeights: 날짜별 급증 가중치.
    private final DemoGenerateConfig config;
    private final List<DemoZone> zones;
    private final Set<Long> targetIds;
    private final Map<LocalDate, Double> surgeWeights;

    /** 모든 구역의 행을 만든다. */
    DemoStatGenerator(DemoGenerateConfig config, List<DemoZone> zones) {
        this(config, zones, zones.stream().map(DemoZone::id).collect(Collectors.toSet()));
    }

    /**
     * @param zones 이웃 짝을 맞출 때 볼 모든 구역
     * @param targetIds 행을 만들 구역 번호
     */
    DemoStatGenerator(DemoGenerateConfig config, List<DemoZone> zones, Set<Long> targetIds) {
        this.config = config;
        // 구역 번호 순으로 고정해 입력 순서에 결과가 좌우되지 않게 한다.
        this.zones =
                zones.stream().sorted(Comparator.comparingLong(DemoZone::id)).toList();
        this.targetIds = Set.copyOf(targetIds);
        // 급증 설정을 날짜로 바로 찾을 수 있게 맵으로 바꾼다. 없으면 빈 맵이라 모든 날의 가중치가 1이 된다.
        this.surgeWeights = config.surgeDays() == null
                ? Map.of()
                : config.surgeDays().stream().collect(Collectors.toMap(SurgeDay::date, SurgeDay::weight));
    }

    /**
     * 그날 만들 구역(targetIds 중 그 시각이 시작~끝 안인 구역)의 시간별 통계를 만든다. 날짜는 어떤 순서로 불러도 된다.
     *
     * @return 구역 번호·시각 순. 그날 만들 구역이 없으면 빈 목록
     */
    List<DemoHourlyStat> generateDay(LocalDate date) {
        // 그날 전체에 곱하는 가중치 = 요일 × 계절 × 특정일 급증.
        double dayWeight = config.dayOfWeekWeights().get(date.getDayOfWeek())
                * config.seasonWeights().get(Season.of(date.getMonth()))
                * surgeWeights.getOrDefault(date, 1.0);
        List<DemoHourlyStat> result = new ArrayList<>();
        // 0~23시를 차례로 만든다.
        for (int hour = 0; hour < DemoGenerateConfig.HOURS_PER_DAY; hour++) {
            LocalDateTime statAt = date.atTime(hour, 0);
            // 이 시각이 시연용 구간(시작~끝)에 드는 구역. 만들 대상이 아닌 이웃도 유입 짝을 맞추려고 포함한다.
            List<DemoZone> activeZones =
                    zones.stream().filter(zone -> zone.covers(statAt)).toList();
            // 만들 구역이 하나도 없는 시각은 건너뛴다.
            if (activeZones.stream().noneMatch(zone -> targetIds.contains(zone.id()))) {
                continue;
            }
            // 이 시각의 가중치 = 그날 가중치 × 시간대 가중치.
            double weight = dayWeight * config.hourWeights().get(hour);
            // 이웃의 유출이 내 유입이 되므로, 짝을 맞추기 전에 구역마다 무작위 값을 먼저 모두 뽑아 둔다.
            Map<Long, DrawnHour> drawn = new HashMap<>();
            for (DemoZone zone : activeZones) {
                drawn.put(zone.id(), drawHour(zone, weight, random(zone, statAt)));
            }
            // 이웃 짝으로 정해지는 유입 값.
            Map<Long, int[]> pairedInflows = pairInflows(activeZones, drawn);
            for (DemoZone zone : activeZones) {
                // 대상 구역만 행으로 만든다. 이웃으로만 쓰인 구역은 값만 쓰이고 돌려주지 않는다.
                if (targetIds.contains(zone.id())) {
                    StatValues values = toValues(zone, drawn.get(zone.id()), pairedInflows.get(zone.id()));
                    result.add(new DemoHourlyStat(zone.id(), statAt, values));
                }
            }
        }
        // 구역 번호, 시각 순으로 돌려준다.
        result.sort(Comparator.comparingLong(DemoHourlyStat::zoneId).thenComparing(DemoHourlyStat::statAt));
        return result;
    }

    // 실행 시점·시작일과 상관없이 (seed, 구역, 절대 시각)만으로 정해지는 난수
    private SplittableRandom random(DemoZone zone, LocalDateTime statAt) {
        // 1970-01-01부터 센 절대 시간 번호. 실행 날짜와 무관하게 같은 시각이면 같은 번호다.
        long hourNumber = statAt.toLocalDate().toEpochDay() * DemoGenerateConfig.HOURS_PER_DAY + statAt.getHour();
        // seed와 구역 번호로 구역별 기준값을 만든다.
        long zoneSeed = config.seed() * ZONE_SEED_MULTIPLIER + zone.id();
        // (seed, 구역, 시각)이 같으면 항상 같은 난수열이 나온다.
        return new SplittableRandom(zoneSeed * HOUR_SEED_MULTIPLIER + hourNumber);
    }

    // 꺼내는 순서: 인원 1개 → 방향별 유출 4개 → 방향별 바깥 유입 4개 → 체류 1개
    private static DrawnHour drawHour(DemoZone zone, double weight, SplittableRandom random) {
        // 평균 인원 = 기본 인원 × 시각 가중치 × 무작위 배율. 소수 둘째 자리로 반올림한다. 난수를 꺼내는 순서가 바뀌면 같은 seed에서도 값이 달라진다.
        BigDecimal countAvg = BigDecimal.valueOf(zone.baseCount() * weight * noise(random, COUNT_NOISE))
                .setScale(2, RoundingMode.HALF_UP);
        // 시간당 구역을 떠나는 인원 = 평균 인원 × 3600 ÷ 평균 체류. 이를 네 방향에 균등하게 나눈다.
        double perDirection = countAvg.doubleValue() * SECONDS_PER_HOUR / zone.avgDwellSec() / DIRECTIONS.length;
        // 방향별 유출 인원: 균등값에 무작위 배율을 곱해 반올림한다.
        int[] outflows = new int[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            outflows[i] = Math.toIntExact(Math.round(perDirection * noise(random, FLOW_NOISE)));
        }
        // 구역 바깥(이웃 없는 방향)에서 들어오는 인원. 이웃과 짝이 지어지면 쓰이지 않는다.
        int[] outsideInflows = new int[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            outsideInflows[i] = Math.toIntExact(Math.round(perDirection * noise(random, FLOW_NOISE)));
        }
        return new DrawnHour(countAvg, outflows, outsideInflows, noise(random, DWELL_NOISE));
    }

    /**
     * 이웃 쌍의 유입을 맞춘다(FNC-A-051 처리 순서 3): A의 d 방향 이웃이 B면 A의 d 유출이 B의 반대 방향 유입이다.
     * 이웃 구역이 그 시각에 시연용 구간이 아니면(캠 가동 뒤, 삭제됨 등) 짝을 짓지 않는다.
     *
     * @return 구역 번호 → 방향별 유입. 짝지어진 방향이 없으면 그 칸은 -1
     */
    private static Map<Long, int[]> pairInflows(List<DemoZone> activeZones, Map<Long, DrawnHour> drawn) {
        // 구역 번호 → 방향별 유입. 짝이 지어지지 않은 칸은 -1이다.
        Map<Long, int[]> inflows = new HashMap<>();
        // 모든 구역의 방향별 유출을 이웃 구역의 반대 방향 유입으로 옮긴다.
        for (DemoZone from : activeZones) {
            int[] outflows = drawn.get(from.id()).outflows();
            from.neighborIds().forEach((direction, toId) -> {
                // 이웃이 이 시각에 시연용 구간이 아니면 짝짓지 않는다(람다라 return은 이 방향만 건너뛴다).
                if (!drawn.containsKey(toId)) {
                    return;
                }
                // 받는 쪽 구역의 유입 배열(처음이면 -1로 채워 만든다).
                int[] target = inflows.computeIfAbsent(toId, id -> emptyInflows());
                // A에서 d 방향으로 나간 사람은 B에서는 반대 방향으로 들어온 것이다.
                int side = direction.opposite().ordinal();
                // -1(짝 없음)이면 0에서 시작해 유출을 더한다.
                target[side] = Math.max(target[side], 0) + outflows[direction.ordinal()];
            });
        }
        return inflows;
    }

    /**
     * 뽑아 둔 값과 짝 맞춘 유입으로 시간별 통계 한 행의 값(StatValues)을 조립한다.
     *
     * @param pairedInflows 이웃 짝으로 정해진 방향별 유입. 짝이 없는 구역이면 null
     */
    private static StatValues toValues(DemoZone zone, DrawnHour drawn, int[] pairedInflows) {
        // 방향별 유입: 이웃과 짝지어진 칸은 이웃의 유출을 쓰고, 아니면 바깥에서 들어오는 값을 쓴다.
        int[] inflows = new int[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            boolean paired = pairedInflows != null && pairedInflows[i] >= 0;
            inflows[i] = paired ? pairedInflows[i] : drawn.outsideInflows()[i];
        }
        int[] outflows = drawn.outflows();
        // 체류 건수 = 구역을 떠난 총 인원(네 방향 유출의 합).
        int dwellCount = 0;
        for (int outflow : outflows) {
            dwellCount += outflow;
        }
        // 체류 시간 합 = 떠난 인원 × 평균 체류 × 무작위 배율.
        long dwellSumSec = Math.round(dwellCount * (double) zone.avgDwellSec() * drawn.dwellFactor());
        BigDecimal countAvg = drawn.countAvg();

        // count_max = avg×1.3, count_min = avg×0.7, density = avg÷면적, sample_sec = 3600(FNC-A-051 처리 순서 4)
        return new StatValues(
                countAvg,
                countAvg.multiply(COUNT_MAX_RATIO)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValueExact(),
                countAvg.multiply(COUNT_MIN_RATIO)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValueExact(),
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

    /**
     * 모든 칸이 -1인 유입 배열을 만든다.
     */
    // 짝지어진 유입이 없는 칸을 -1로 표시한다(0명 유입과 구분)
    private static int[] emptyInflows() {
        int[] inflows = new int[DIRECTIONS.length];
        Arrays.fill(inflows, -1);
        return inflows;
    }

    /**
     * 1 - ratio ~ 1 + ratio 사이의 무작위 배율을 돌려준다.
     */
    // 1 ± ratio 사이의 배율
    private static double noise(SplittableRandom random, double ratio) {
        return 1 + (random.nextDouble() * 2 - 1) * ratio;
    }

    /** 한 구역·한 시각에 꺼낸 무작위 값으로 정한 인원·유출·바깥 유입·체류 배율. */
    private record DrawnHour(BigDecimal countAvg, int[] outflows, int[] outsideInflows, double dwellFactor) {}
}
