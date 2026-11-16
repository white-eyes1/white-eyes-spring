package com.whiteeyes.tools.demo.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.service.StatRollupCalculator;
import com.whiteeyes.tools.demo.repository.DemoDailyStat;
import com.whiteeyes.tools.demo.repository.DemoHourlyStat;
import com.whiteeyes.tools.demo.repository.DemoStatRepository;
import com.whiteeyes.tools.demo.service.DemoGenerateConfig.ZoneSetting;

/**
 * 시연용 통계 생성(FNC-A-051). 규칙으로 과거 시간별 통계를 만들고, 일별 통계는 그 시간별을 묶어 만든다.
 * 1분 통계는 만들지 않는다. 만들 구역의 기존 시연 데이터가 있으면 먼저 지우고 다시 만든다.
 *
 * <p>구역마다 끝 시각(캠 첫 가동 시각이 속한 정시, 가동한 적 없으면 실행 시각이 속한 정시) 직전까지 만들고,
 * 시작은 끝 시각의 1년 전 날짜 0시다. 그래서 첫 가동 뒤에 실행하면 실제 통계와 시간별·일별이 끊기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoGenerateService {

    // 협력 객체: 설정 읽기, 삭제, JDBC 일괄 삽입, 구역·이웃·캠 조회, 시간별·일별 통계 조회와 저장, 날짜별 트랜잭션, 시계(KST).
    private final DemoConfigReader configReader;
    private final DemoDeleteService demoDeleteService;
    private final DemoStatRepository demoStatRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneNeighborRepository zoneNeighborRepository;
    private final CameraRepository cameraRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final ZoneStatDailyRepository zoneStatDailyRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

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
        // 설정을 먼저 읽고 검사한다. 틀리면 여기서 예외가 나므로 기존 데이터는 지워지지 않는다.
        DemoGenerateConfig config = configFile == null ? configReader.defaults() : configReader.read(configFile);
        // 실행 시각. 캠이 한 번도 가동되지 않은 구역의 끝 시각을 정하는 데 쓴다.
        LocalDateTime now = LocalDateTime.now(clock);
        // 삭제되지 않은 모든 구역과 구역별 시작·끝 시각을 구한다(이웃 짝을 맞추려고 만들지 않을 구역도 포함).
        List<DemoZone> zones = loadZones(config, now);
        // 실제로 만들 구역. --zone이 없으면 전체이고, 잘못된 구역이 있으면 삭제 전에 예외가 난다.
        Set<Long> targetIds = targetIds(zones, zoneIds);

        // --zone이 없으면 모든 시연용 행을, 있으면 대상 구역의 행만 지운다. 지운 뒤 다시 만들기 때문에 같은 명령을 여러 번 실행해도 중복되지 않는다.
        DemoDeleteResult deleted =
                zoneIds == null ? demoDeleteService.deleteAll() : demoDeleteService.deleteZones(targetIds);
        log.info("기존 시연용 데이터 삭제 시간별={} 일별={}", deleted.hourly(), deleted.daily());

        // 만들 대상 구역만 추린다.
        List<DemoZone> targets =
                zones.stream().filter(zone -> targetIds.contains(zone.id())).toList();
        targets.forEach(zone -> log.info("시연용 통계 만들 구간 zoneId={} 시작={} 끝(제외)={}", zone.id(), zone.start(), zone.end()));
        // 만들 구역이 없으면 지운 건수만 알리고 끝낸다.
        if (targets.isEmpty()) {
            return new DemoGenerateResult(0, 0, 0, deleted);
        }

        // 규칙에 따라 값을 계산하는 생성기. 이웃 짝을 맞추려고 전체 구역과 만들 구역을 모두 넘긴다.
        DemoStatGenerator generator = new DemoStatGenerator(config, zones, targetIds);
        // 대상 구역 중 가장 이른 시작 날짜부터 가장 늦은 마지막 날짜까지 하루씩 돈다.
        LocalDate firstDate = targets.stream()
                .map(zone -> zone.start().toLocalDate())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate lastDate = targets.stream()
                .map(zone -> zone.end().minusHours(1).toLocalDate())
                .max(Comparator.naturalOrder())
                .orElseThrow();
        // 넣은 건수 합계(결과 보고용).
        long hourly = 0;
        long daily = 0;
        // 날짜 하나씩 생성·저장한다. 날짜마다 커밋하므로 메모리·트랜잭션이 커지지 않는다.
        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            // 그날의 시간별 통계(구역·시각 순).
            List<DemoHourlyStat> hours = generator.generateDay(date);
            // 그날 만들 구역이 없으면 건너뛴다.
            if (hours.isEmpty()) {
                continue;
            }
            // 그날 24시간이 모두 시연용인 구역만 시간별을 묶어 일별을 만든다.
            List<DemoDailyStat> days = rollUpFullDays(date, hours);
            // 같은 날의 시간별·일별을 한 트랜잭션으로 저장한다. 함께 성공하거나 함께 취소되어 "일별 = 시간별 합"이 깨지지 않는다.
            transactionTemplate.executeWithoutResult(status -> {
                demoStatRepository.insertHourly(hours);
                demoStatRepository.insertDaily(days);
            });
            hourly += hours.size();
            daily += days.size();
        }
        // 끝 시각이 든 날은 시연용과 실제 시간별이 섞여 있어 위 루프에서 일별을 만들지 못했다. 따로 채운다.
        daily += fillEndDays(targets, now.toLocalDate());
        return new DemoGenerateResult(targets.size(), hourly, daily, deleted);
    }

    // 이웃 짝을 맞추려고 만들지 않는 구역까지 모두 읽는다
    private List<DemoZone> loadZones(DemoGenerateConfig config, LocalDateTime now) {
        // 삭제되지 않은 구역을 번호 순으로 읽는다.
        List<Zone> summaries = zoneRepository.findByDeletedAtIsNullOrderById();
        Set<Long> zoneIds = summaries.stream().map(Zone::getId).collect(Collectors.toSet());
        // 설정 파일의 구역별 값을 구역 번호로 찾을 수 있게 한다.
        Map<Long, ZoneSetting> settings =
                config.zones().stream().collect(Collectors.toMap(ZoneSetting::zoneId, Function.identity()));
        // 설정에는 있지만 DB에 없거나 삭제된 구역은 경고만 남기고 무시한다.
        settings.keySet().stream()
                .filter(zoneId -> !zoneIds.contains(zoneId))
                .forEach(zoneId -> log.warn("설정의 구역이 없거나 삭제되어 건너뜀 zoneId={}", zoneId));

        // 구역별 방향 → 이웃 구역 번호.
        Map<Long, Map<ZoneDirection, Long>> neighbors = findNeighborIds(zoneIds);
        // 구역에 캠이 여러 대면 가장 이른 첫 가동 시각을 쓴다(minBy). 이 시각부터는 실제 통계가 쌓이므로 시연용의 끝이 된다.
        Map<Long, LocalDateTime> firstStartedAt = cameraRepository.findByFirstStartedAtIsNotNull().stream()
                .collect(Collectors.toMap(
                        Camera::getZoneId, Camera::getFirstStartedAt, BinaryOperator.minBy(LocalDateTime::compareTo)));

        List<DemoZone> zones = new ArrayList<>();
        for (Zone summary : summaries) {
            // 구역별 설정이 없으면 기본 구역 설정을 쓴다.
            ZoneSetting setting = settings.getOrDefault(summary.getId(), config.defaultZone());
            zones.add(DemoZone.of(
                    summary.getId(),
                    summary.getAreaM2(),
                    setting.baseCount(),
                    setting.avgDwellSec(),
                    firstStartedAt.get(summary.getId()),
                    now,
                    neighbors.getOrDefault(summary.getId(), Map.of())));
        }
        return zones;
    }

    // --zone이 없으면 모든 구역. 있으면 모두 삭제되지 않은 구역이어야 한다(지우기 전에 확인)
    private static Set<Long> targetIds(List<DemoZone> zones, Collection<Long> zoneIds) {
        // 삭제되지 않은 구역 번호(정렬된 집합).
        Set<Long> activeIds = zones.stream().map(DemoZone::id).collect(Collectors.toCollection(TreeSet::new));
        if (zoneIds == null) {
            return activeIds;
        }
        if (zoneIds.isEmpty()) {
            throw new IllegalArgumentException("--zone에 구역 번호가 없습니다");
        }
        // --zone에 적었지만 없거나 삭제된 구역. 지우기 전에 확인해 기존 데이터를 지킨다.
        List<Long> unknown = zoneIds.stream()
                .filter(id -> !activeIds.contains(id))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("--zone의 구역이 없거나 삭제되었습니다: " + unknown);
        }
        return new TreeSet<>(zoneIds);
    }

    // 일별은 정시 집계(FNC-A-030)와 같은 계산으로 그날 시간별을 묶는다. 그래서 일별 = 시간별 합이 된다.
    // 24시간이 다 시연용인 구역만 묶는다(끝 시각이 든 날은 실제 통계와 함께 묶어야 한다)
    private static List<DemoDailyStat> rollUpFullDays(LocalDate date, List<DemoHourlyStat> hours) {
        // 그날의 시간별 값을 구역별로 모은다(LinkedHashMap으로 입력 순서를 유지한다).
        Map<Long, List<StatValues>> byZone = hours.stream()
                .collect(Collectors.groupingBy(
                        DemoHourlyStat::zoneId,
                        LinkedHashMap::new,
                        Collectors.mapping(DemoHourlyStat::values, Collectors.toList())));
        List<DemoDailyStat> days = new ArrayList<>();
        byZone.forEach((zoneId, values) -> {
            // 24시간이 모두 있는 구역만 일별을 만든다. 일부만 시연용인 날은 실제 통계와 함께 묶어야 하므로 여기서는 만들지 않는다.
            if (values.size() == DemoGenerateConfig.HOURS_PER_DAY) {
                // 실제 정시 집계와 같은 계산기를 써서 시연용과 실제 일별 값이 같은 규칙으로 나오게 한다.
                StatRollupCalculator.rollUp(values)
                        .ifPresent(rolledUp -> days.add(new DemoDailyStat(zoneId, date, rolledUp)));
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
        // 끝 날짜 → 그 날짜가 끝 시각이 든 구역들(TreeMap이라 날짜 순).
        Map<LocalDate, Set<Long>> zoneIdsByDate = new TreeMap<>();
        for (DemoZone zone : targets) {
            LocalDate endDate = zone.end().toLocalDate();
            // 끝이 정각 0시이면 끝 날짜에는 시연용 시간이 없어(앞날까지 모두 시연용) 이미 일별이 만들어졌다. 오늘이면 자정 집계가 만든다.
            if (!zone.end().toLocalTime().equals(LocalTime.MIDNIGHT) && endDate.isBefore(today)) {
                zoneIdsByDate.computeIfAbsent(endDate, date -> new TreeSet<>()).add(zone.id());
            }
        }
        long filled = 0;
        for (Map.Entry<LocalDate, Set<Long>> entry : zoneIdsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            // 그날 시간별은 있는데 일별이 아직 없는 구역만 대상으로 한다. 이미 있는 일별(실제만 묶은 행 등)은 건드리지 않는다.
            List<Long> missing =
                    zoneStatHourlyRepository
                            .findZoneIdsMissingDaily(
                                    date, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                            .stream()
                            .filter(entry.getValue()::contains)
                            .toList();
            if (!missing.isEmpty()) {
                // 그날의 시간별 행(시연용과 실제 모두)을 읽어 구역별로 묶는다.
                Map<Long, List<ZoneStatHourly>> hoursByZone = zoneStatHourlyRepository
                        .findByStatAtGreaterThanEqualAndStatAtLessThan(
                                date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                        .stream()
                        .filter(hour -> missing.contains(hour.getZoneId()))
                        .collect(Collectors.groupingBy(ZoneStatHourly::getZoneId));
                // 일별 집계와 같은 계산(ZoneStatDaily.rollUp)으로 만들어 저장한다.
                hoursByZone.forEach((zoneId, hours) ->
                        ZoneStatDaily.rollUp(zoneId, date, hours).ifPresent(zoneStatDailyRepository::save));
                filled += missing.size();
            }
        }
        return filled;
    }

    /**
     * 구역별 이웃 정보를 읽어 구역 번호 → (방향 → 이웃 구역 번호)로 정리한다.
     */
    private Map<Long, Map<ZoneDirection, Long>> findNeighborIds(Set<Long> zoneIds) {
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        for (ZoneNeighbor neighbor : zoneNeighborRepository.findByZoneIdIn(zoneIds)) {
            result.computeIfAbsent(neighbor.getZoneId(), zoneId -> new EnumMap<>(ZoneDirection.class))
                    .put(neighbor.getDirection(), neighbor.getNeighborId());
        }
        return result;
    }
}
