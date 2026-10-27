package com.whiteeyes.admin.demo.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.demo.repository.DemoDailyStat;
import com.whiteeyes.admin.demo.repository.DemoHourlyStat;
import com.whiteeyes.admin.demo.repository.DemoStatRepository;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.ZoneSetting;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.ZoneNeighborService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.admin.stat.service.DailyStatService;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.service.StatRollupCalculator;

/**
 * 시연용 통계 생성(FNC-A-051). 규칙으로 과거 시간별 통계를 만들고, 일별 통계는 그 시간별을 묶어 만든다.
 * 1분 통계는 만들지 않는다. 만들 구역의 기존 시연 데이터가 있으면 먼저 지우고 다시 만든다.
 *
 * <p>구역마다 끝 시각(캠 첫 가동 시각이 속한 정시, 가동한 적 없으면 실행 시각이 속한 정시) 직전까지 만들고,
 * 시작은 끝 시각의 1년 전 날짜 0시다. 그래서 첫 가동 뒤에 실행하면 실제 통계와 시간별·일별이 끊기지 않는다.
 */
@Service
public class DemoGenerateService {

    private static final Logger log = LoggerFactory.getLogger(DemoGenerateService.class);

    private final DemoConfigReader configReader;
    private final DemoDeleteService demoDeleteService;
    private final DemoStatRepository demoStatRepository;
    private final ZoneService zoneService;
    private final ZoneNeighborService zoneNeighborService;
    private final CameraQueryService cameraQueryService;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final DailyStatService dailyStatService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DemoGenerateService(
            DemoConfigReader configReader,
            DemoDeleteService demoDeleteService,
            DemoStatRepository demoStatRepository,
            ZoneService zoneService,
            ZoneNeighborService zoneNeighborService,
            CameraQueryService cameraQueryService,
            ZoneStatHourlyRepository zoneStatHourlyRepository,
            DailyStatService dailyStatService,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.configReader = configReader;
        this.demoDeleteService = demoDeleteService;
        this.demoStatRepository = demoStatRepository;
        this.zoneService = zoneService;
        this.zoneNeighborService = zoneNeighborService;
        this.cameraQueryService = cameraQueryService;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
        this.dailyStatService = dailyStatService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 날짜마다 시간별을 저장한 뒤 같은 날의 일별을 저장한다(처리 순서 5). 같은 날의 시간별·일별은 한 트랜잭션이라
     * 그 사이에 실패해도 시간별만 남지 않는다(일별 = 시간별 합 유지). 날짜마다 커밋되므로 중간에 실패하면 다시 실행한다
     * (다시 실행하면 먼저 지우고 처음부터 만든다).
     *
     * <p>일별은 그날 24시간이 모두 시연용인 날만 만든다. 끝 시각이 든 날은 나머지 시간이 실제 통계라서
     * 오늘이면 자정 집계(FNC-A-030)가, 지난 날이면 아래 {@link #fillEndDays}가 시연용과 실제를 함께 묶는다.
     *
     * @param configFile 설정 파일(JSON). null이면 기본 설정
     * @param zoneIds 만들 구역 번호(--zone). null이면 삭제되지 않은 모든 구역이고, 그때는 모든 시연용 행을 지운다
     * @throws IllegalArgumentException 설정이 틀렸거나 없는·삭제된 구역을 지정했을 때. 이때는 기존 데이터를 지우지 않는다
     */
    public DemoGenerateResult generate(Path configFile, Collection<Long> zoneIds) {
        DemoGenerateConfig config = configFile == null ? configReader.defaults() : configReader.read(configFile);
        LocalDateTime now = LocalDateTime.now(clock);
        List<DemoZone> zones = loadZones(config, now);
        Set<Long> targetIds = targetIds(zones, zoneIds);

        DemoDeleteResult deleted =
                zoneIds == null ? demoDeleteService.deleteAll() : demoDeleteService.deleteZones(targetIds);
        log.info("기존 시연용 데이터 삭제 시간별={} 일별={}", deleted.hourly(), deleted.daily());

        List<DemoZone> targets =
                zones.stream().filter(zone -> targetIds.contains(zone.id())).toList();
        targets.forEach(zone -> log.info(
                "시연용 통계 만들 구간 zoneId={} 시작={} 끝(제외)={}", zone.id(), zone.start(), zone.end()));
        if (targets.isEmpty()) {
            return new DemoGenerateResult(0, 0, 0, deleted);
        }

        DemoStatGenerator generator = new DemoStatGenerator(config, zones, targetIds);
        LocalDate firstDate = targets.stream()
                .map(zone -> zone.start().toLocalDate())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate lastDate = targets.stream()
                .map(zone -> zone.end().minusHours(1).toLocalDate())
                .max(Comparator.naturalOrder())
                .orElseThrow();
        long hourly = 0;
        long daily = 0;
        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            List<DemoHourlyStat> hours = generator.generateDay(date);
            if (hours.isEmpty()) {
                continue;
            }
            List<DemoDailyStat> days = rollUpFullDays(date, hours);
            transactionTemplate.executeWithoutResult(status -> {
                demoStatRepository.insertHourly(hours);
                demoStatRepository.insertDaily(days);
            });
            hourly += hours.size();
            daily += days.size();
        }
        daily += fillEndDays(targets, now.toLocalDate());
        return new DemoGenerateResult(targets.size(), hourly, daily, deleted);
    }

    // 이웃 짝을 맞추려고 만들지 않는 구역까지 모두 읽는다
    private List<DemoZone> loadZones(DemoGenerateConfig config, LocalDateTime now) {
        List<ZoneSummary> summaries = zoneService.findActiveSummaries();
        Set<Long> zoneIds = summaries.stream().map(ZoneSummary::id).collect(Collectors.toSet());
        Map<Long, ZoneSetting> settings =
                config.zones().stream().collect(Collectors.toMap(ZoneSetting::zoneId, Function.identity()));
        settings.keySet().stream()
                .filter(zoneId -> !zoneIds.contains(zoneId))
                .forEach(zoneId -> log.warn("설정의 구역이 없거나 삭제되어 건너뜀 zoneId={}", zoneId));

        Map<Long, Map<ZoneDirection, Long>> neighbors = zoneNeighborService.findNeighborIds(zoneIds);
        Map<Long, LocalDateTime> firstStartedAt = cameraQueryService.findFirstStartedAtByZone();

        List<DemoZone> zones = new ArrayList<>();
        for (ZoneSummary summary : summaries) {
            ZoneSetting setting = settings.getOrDefault(summary.id(), config.defaultZone());
            zones.add(DemoZone.of(
                    summary.id(),
                    summary.areaM2(),
                    setting.baseCount(),
                    setting.avgDwellSec(),
                    firstStartedAt.get(summary.id()),
                    now,
                    neighbors.getOrDefault(summary.id(), Map.of())));
        }
        return zones;
    }

    // --zone이 없으면 모든 구역. 있으면 모두 삭제되지 않은 구역이어야 한다(지우기 전에 확인)
    private static Set<Long> targetIds(List<DemoZone> zones, Collection<Long> zoneIds) {
        Set<Long> activeIds = zones.stream().map(DemoZone::id).collect(Collectors.toCollection(TreeSet::new));
        if (zoneIds == null) {
            return activeIds;
        }
        if (zoneIds.isEmpty()) {
            throw new IllegalArgumentException("--zone에 구역 번호가 없습니다");
        }
        List<Long> unknown = zoneIds.stream().filter(id -> !activeIds.contains(id)).distinct().toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("--zone의 구역이 없거나 삭제되었습니다: " + unknown);
        }
        return new TreeSet<>(zoneIds);
    }

    // 일별은 정시 집계(FNC-A-030)와 같은 계산으로 그날 시간별을 묶는다. 그래서 일별 = 시간별 합이 된다.
    // 24시간이 다 시연용인 구역만 묶는다(끝 시각이 든 날은 실제 통계와 함께 묶어야 한다)
    private static List<DemoDailyStat> rollUpFullDays(LocalDate date, List<DemoHourlyStat> hours) {
        Map<Long, List<StatValues>> byZone = hours.stream()
                .collect(Collectors.groupingBy(
                        DemoHourlyStat::zoneId,
                        LinkedHashMap::new,
                        Collectors.mapping(DemoHourlyStat::values, Collectors.toList())));
        List<DemoDailyStat> days = new ArrayList<>();
        byZone.forEach((zoneId, values) -> {
            if (values.size() == DemoGenerateConfig.HOURS_PER_DAY) {
                StatRollupCalculator.rollUp(values).ifPresent(rolledUp -> days.add(new DemoDailyStat(zoneId, date, rolledUp)));
            }
        });
        return days;
    }

    /**
     * 끝 시각이 든 날이 오늘보다 앞이면 그날 일별을 일별 집계(FNC-A-030)로 다시 묶는다. 그날은 시연용 시간별과
     * 첫 가동 뒤 실제 시간별이 섞여 있다. 그날 일별이 이미 있으면(첫 가동 뒤 자정 집계가 실제만 묶은 행, is_demo=0)
     * 건드리지 않는다. 오늘은 자정 집계가 만든다.
     *
     * @return 만든 일별 건수
     */
    private long fillEndDays(List<DemoZone> targets, LocalDate today) {
        Map<LocalDate, Set<Long>> zoneIdsByDate = new TreeMap<>();
        for (DemoZone zone : targets) {
            LocalDate endDate = zone.end().toLocalDate();
            if (!zone.end().toLocalTime().equals(LocalTime.MIDNIGHT) && endDate.isBefore(today)) {
                zoneIdsByDate.computeIfAbsent(endDate, date -> new TreeSet<>()).add(zone.id());
            }
        }
        long filled = 0;
        for (Map.Entry<LocalDate, Set<Long>> entry : zoneIdsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<Long> missing = zoneStatHourlyRepository
                    .findZoneIdsMissingDaily(date, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                    .stream()
                    .filter(entry.getValue()::contains)
                    .toList();
            if (!missing.isEmpty()) {
                dailyStatService.aggregateDay(date, missing);
                filled += missing.size();
            }
        }
        return filled;
    }
}
