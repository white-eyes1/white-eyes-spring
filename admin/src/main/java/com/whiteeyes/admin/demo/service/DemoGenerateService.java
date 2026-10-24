package com.whiteeyes.admin.demo.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.demo.repository.DemoDailyStat;
import com.whiteeyes.admin.demo.repository.DemoHourlyStat;
import com.whiteeyes.admin.demo.repository.DemoStatRepository;
import com.whiteeyes.admin.demo.service.DemoGenerateConfig.ZoneSetting;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.ZoneNeighborService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.service.StatRollupCalculator;

/**
 * 시연용 통계 생성(FNC-A-051). 규칙으로 과거 시간별 통계를 만들고, 일별 통계는 그 시간별을 묶어 만든다.
 * 1분 통계는 만들지 않는다. 기존 시연 데이터가 있으면 먼저 지우고 다시 만든다.
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
    private final Clock clock;

    public DemoGenerateService(
            DemoConfigReader configReader,
            DemoDeleteService demoDeleteService,
            DemoStatRepository demoStatRepository,
            ZoneService zoneService,
            ZoneNeighborService zoneNeighborService,
            CameraQueryService cameraQueryService,
            Clock clock) {
        this.configReader = configReader;
        this.demoDeleteService = demoDeleteService;
        this.demoStatRepository = demoStatRepository;
        this.zoneService = zoneService;
        this.zoneNeighborService = zoneNeighborService;
        this.cameraQueryService = cameraQueryService;
        this.clock = clock;
    }

    /**
     * 날짜마다 시간별을 저장한 뒤 같은 날의 일별을 저장한다(처리 순서 5). 날짜마다 따로 저장되므로 중간에 실패하면 다시 실행한다
     * (다시 실행하면 먼저 지우고 처음부터 만든다).
     *
     * @param configFile 설정 파일(JSON)
     * @throws IllegalArgumentException 설정이 틀렸거나 설정이 없는 구역이 있을 때. 이때는 기존 데이터를 지우지 않는다
     */
    public DemoGenerateResult generate(Path configFile) {
        DemoGenerateConfig config = configReader.read(configFile);
        List<DemoZone> zones = loadZones(config);

        DemoDeleteResult deleted = demoDeleteService.deleteAll();
        log.info("기존 시연용 데이터 삭제 시간별={} 일별={}", deleted.hourly(), deleted.daily());

        List<DemoZone> targets = zones.stream()
                .filter(zone -> !zone.endDate().isBefore(config.startDate()))
                .toList();
        DemoStatGenerator generator = new DemoStatGenerator(config, targets);
        LocalDate lastDate = targets.stream()
                .map(DemoZone::endDate)
                .max(Comparator.naturalOrder())
                .orElse(config.startDate().minusDays(1));
        long hourly = 0;
        long daily = 0;
        for (LocalDate date = config.startDate(); !date.isAfter(lastDate); date = date.plusDays(1)) {
            List<DemoHourlyStat> hours = generator.generateDay(date);
            demoStatRepository.insertHourly(hours);
            List<DemoDailyStat> days = rollUpDay(date, hours);
            demoStatRepository.insertDaily(days);
            hourly += hours.size();
            daily += days.size();
        }
        return new DemoGenerateResult(targets.size(), hourly, daily, deleted);
    }

    private List<DemoZone> loadZones(DemoGenerateConfig config) {
        List<ZoneSummary> summaries = zoneService.findActiveSummaries();
        Set<Long> zoneIds = summaries.stream().map(ZoneSummary::id).collect(Collectors.toSet());
        Map<Long, ZoneSetting> settings = config.zones() == null
                ? Map.of()
                : config.zones().stream().collect(Collectors.toMap(ZoneSetting::zoneId, Function.identity()));
        settings.keySet().stream()
                .filter(zoneId -> !zoneIds.contains(zoneId))
                .forEach(zoneId -> log.warn("설정의 구역이 없거나 삭제되어 건너뜀 zoneId={}", zoneId));

        Map<Long, Map<ZoneDirection, Long>> neighbors = zoneNeighborService.findNeighborIds(zoneIds);
        Map<Long, LocalDateTime> firstStartedAt = cameraQueryService.findFirstStartedAtByZone();
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);

        List<DemoZone> zones = new ArrayList<>();
        for (ZoneSummary summary : summaries) {
            ZoneSetting setting = settings.getOrDefault(summary.id(), config.defaultZone());
            if (setting == null) {
                throw new IllegalArgumentException(
                        "시연용 통계 설정이 틀렸습니다: zoneId=" + summary.id() + "의 설정이 없고 defaultZone도 없습니다");
            }
            zones.add(new DemoZone(
                    summary.id(),
                    summary.areaM2(),
                    setting.baseCount(),
                    setting.avgDwellSec(),
                    endDate(firstStartedAt.get(summary.id()), yesterday),
                    neighbors.getOrDefault(summary.id(), Map.of())));
        }
        return zones;
    }

    // 종료일 = 그 구역 캠의 first_started_at 전날, 없으면 어제(처리 순서 1). 실제 데이터와 겹치지 않게 한다
    private static LocalDate endDate(LocalDateTime firstStartedAt, LocalDate yesterday) {
        if (firstStartedAt == null) {
            return yesterday;
        }
        LocalDate dayBeforeStart = firstStartedAt.toLocalDate().minusDays(1);
        return dayBeforeStart.isBefore(yesterday) ? dayBeforeStart : yesterday;
    }

    // 일별은 정시 집계(FNC-A-030)와 같은 계산으로 그날 시간별을 묶는다. 그래서 일별 = 시간별 합이 된다
    private static List<DemoDailyStat> rollUpDay(LocalDate date, List<DemoHourlyStat> hours) {
        Map<Long, List<StatValues>> byZone = hours.stream()
                .collect(Collectors.groupingBy(
                        DemoHourlyStat::zoneId,
                        LinkedHashMap::new,
                        Collectors.mapping(DemoHourlyStat::values, Collectors.toList())));
        List<DemoDailyStat> days = new ArrayList<>();
        byZone.forEach((zoneId, values) -> StatRollupCalculator.rollUp(values)
                .ifPresent(rolledUp -> days.add(new DemoDailyStat(zoneId, date, rolledUp))));
        return days;
    }
}
