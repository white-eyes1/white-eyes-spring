package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.service.CameraCountService;
import com.whiteeyes.common.event.service.TodayEventCountService;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.DashboardSummaryResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.stat.service.RecentZoneStatReader.LatestMinuteSum;

/**
 * 현재 현황 요약(FNC-C-016). 선택한 구역·지역의 최근 1분 인원·밀집도·단계·유입/유출·주요 방향과 캠 수, 오늘 최대 인원을 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class DashboardSummaryService {

    // 응답의 통계 시각에 붙이는 한국 시간 오프셋(+09:00). 한국은 서머타임이 없어 고정값으로 충분하다
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // 유출이 같으면 앞의 방향(FNC-C-016 처리 순서 2)
    private static final List<String> DIRECTIONS = List.of("N", "E", "S", "W");

    // 지역 → 읍·면·동 → 구역 목록과 구역 존재 확인(없으면 404)을 맡는다
    private final RegionQueryService regionQueryService;
    // 대상 구역들의 카메라 수와 가동 중인 카메라 수를 센다
    private final CameraCountService cameraCountService;
    // 최근 5분 안의 가장 늦은 1분 통계와 그 합산을 지도 현황(FNC-C-009)과 같은 기준으로 읽는다
    private final RecentZoneStatReader recentZoneStatReader;
    // 오늘 최대 인원을 구하려고 1분 통계를 직접 조회한다
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 밀집도를 혼잡 단계(1~3)로 바꾼다(대상별 기준값을 쓴다)
    private final LevelService levelService;
    // 지역 합산 때 '지역 안 구역끼리 주고받은 이동'을 빼려고 구역의 방향별 이웃을 읽는다
    private final ZoneNeighborRepository zoneNeighborRepository;
    // 오늘 발생한 이벤트 수를 센다
    private final TodayEventCountService todayEventCountService;
    // 관리자 앱에만 있다. 사용자 앱은 빈이 없어 currentCount가 null이다(결정 0018)
    private final ObjectProvider<NowCountSource> nowCountSource;

    /**
     * @param now KST 현재 시각
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public DashboardSummaryResponse findSummary(DashboardTargetType targetType, long targetId, LocalDateTime now) {
        // 대상 종류에 따라 구역 하나 또는 지역 아래 구역 모음을 준비한다. 없는 대상이면 여기서 404
        Target target = targetType == DashboardTargetType.ZONE ? zoneTarget(targetId) : regionTarget(targetId);
        // 삭제되지 않은 구역 번호. 현재 인원·카메라 수는 이 구역들만 센다
        List<Long> activeZoneIds =
                target.activeZones().stream().map(Zone::getId).toList();
        int cameraCount = cameraCountService.countCameras(activeZoneIds);
        int runningCameraCount = cameraCountService.countRunningCameras(activeZoneIds);
        // 오늘 기록은 삭제된 구역까지 포함한다. 통계가 없으면 null
        Integer todayMaxCount = findTodayMaxCount(target.allZoneIds(), now);
        // 통계가 없어도 센다. 삭제된 구역의 오늘 이벤트도 포함(FNC-C-017)
        int todayEventCount = todayEventCountService.countTotal(
                target.allZoneIds(), now.toLocalDate().atStartOfDay());

        // 5초마다 갱신되는 현재 인원(FNC-A-054, 관리자 앱만). 1분 통계가 아직 없어도 값이 있을 수 있다
        // 빈이 없는 사용자 앱에서는 null이 되고, 그러면 현재 인원은 null로 내려간다
        NowCountSource nowCounts = nowCountSource.getIfAvailable();
        Integer currentCount = nowCounts == null
                ? null
                : nowCounts.sumFresh(activeZoneIds, now).stream()
                        .boxed()
                        .findFirst()
                        .orElse(null);

        // 구역별로 최근 5분 안의 가장 늦은 1분 통계를 읽는다
        Map<Long, ZoneStatMinute> latestStatByZone = recentZoneStatReader.findLatestStats(target.activeZones(), now);
        return recentZoneStatReader
                // 가장 늦은 1분(T)의 구역들을 합산한다. 최근 5분 통계가 하나도 없으면 빈 값
                .sumAtLatestMinute(target.activeZones(), latestStatByZone)
                .map(sum -> toResponse(
                        target, sum, currentCount, cameraCount, runningCameraCount, todayEventCount, todayMaxCount))
                // 통계가 없으면 통계 값(시각·평균·밀집도·단계·유입·유출·방향)은 null로 두되, 통계와 무관한 값(현재 인원·이벤트·카메라·오늘 최대)은 채워 보낸다
                .orElseGet(() -> new DashboardSummaryResponse(
                        null,
                        null,
                        currentCount,
                        null,
                        null,
                        null,
                        null,
                        null,
                        todayEventCount,
                        cameraCount,
                        runningCameraCount,
                        todayMaxCount));
    }

    private Target zoneTarget(long zoneId) {
        // 삭제되지 않은 구역만 허용한다(없거나 삭제됐으면 404)
        Zone zone = regionQueryService.getActiveZone(zoneId);
        // 구역 하나라서 현재 값용 구역 목록과 오늘 기록용 번호 목록이 같다
        return new Target(LevelTarget.zone(zoneId), List.of(zone), List.of(zoneId), true);
    }

    // 현재 값·캠 수는 삭제되지 않은 구역으로, 오늘 기록은 삭제된 구역까지 포함해 센다(FNC-C-017과 같은 기준)
    private Target regionTarget(long regionId) {
        // 지역을 찾는다. 없으면 404
        Region region = regionQueryService.getRegion(regionId);
        List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream()
                .map(Region::getId)
                .toList();
        if (dongIds.isEmpty()) {
            // 읍·면·동이 없는 지역은 구역도 없다
            return new Target(LevelTarget.region(regionId), List.of(), List.of(), false);
        }
        return new Target(
                LevelTarget.region(regionId),
                // 현재 값·카메라 수용: 삭제되지 않은 구역
                regionQueryService.findZonesInDongs(dongIds),
                // 오늘 기록용: 삭제된 구역까지 포함한 구역 번호
                regionQueryService.findAllZoneIdsInDongs(dongIds),
                false);
    }

    // 같은 시각끼리 소속 구역 count_max를 더한 값 중 오늘 최댓값(FNC-C-016 처리 순서 5). 구역 하나면 그 구역의 count_max 최댓값
    private Integer findTodayMaxCount(List<Long> zoneIds, LocalDateTime now) {
        // 구역이 없으면 빈 IN 쿼리를 피하고 통계 없음(null)으로 끝낸다
        if (zoneIds.isEmpty()) {
            return null;
        }
        return zoneStatMinuteRepository
                // 오늘 0시 이후 1분 통계를 시각별로 묶어 구역들의 count_max를 더한 값들을 받는다
                .sumCountMaxByStatAt(zoneIds, now.toLocalDate().atStartOfDay())
                .stream()
                // 그중 가장 큰 값이 '오늘 최대 인원'. 구역 하나면 합이 곧 그 구역의 count_max이다
                .max(Long::compare)
                .map(Long::intValue)
                .orElse(null);
    }

    private DashboardSummaryResponse toResponse(
            Target target,
            LatestMinuteSum sum,
            Integer currentCount,
            int cameraCount,
            int runningCameraCount,
            int todayEventCount,
            Integer todayMaxCount) {
        // 구역 하나면 저장된 밀집도(당시 면적 기준)를 그대로 쓴다. 지역은 현재 면적 합으로 나눈 값(#20과 같은 규칙)
        // 구역 하나는 1분 통계에 저장된 밀집도(당시 면적 기준)를 그대로 쓰고, 지역은 합산 인원 ÷ 현재 면적 합으로 다시 계산한 값을 쓴다
        BigDecimal density = target.isZone() ? sum.stats().get(0).getDensity() : sum.density();
        // 방향(N·E·S·W 순서)별 유입·유출 누적 배열. 인덱스는 DIRECTIONS와 ZoneDirection의 순서값(ordinal)이 같다는 전제다
        int[] inByDirection = new int[DIRECTIONS.size()];
        int[] outByDirection = new int[DIRECTIONS.size()];
        // 지역은 지역 안 구역끼리 주고받은 이동을 뺀다(stats-008): 이웃이 같은 지역 구역인 방향의 유입·유출은 세지 않는다.
        // 구역 하나는 이웃이 자기 밖이므로 그대로 센다
        // 구역 하나면 이웃이 모두 대상 밖이라 따로 읽지 않는다. 지역이면 구역별 방향 → 이웃 구역 번호를 한 번에 읽는다
        Map<Long, Map<ZoneDirection, Long>> neighborsByZone = target.isZone() ? Map.of() : findNeighbors(sum.stats());
        // 이웃이 '이 지역 안의 구역'인지 빠르게 판단하려고 대상 구역 번호를 집합으로 만든다
        Set<Long> targetZoneIds = new HashSet<>();
        target.activeZones().forEach(zone -> targetZoneIds.add(zone.getId()));
        for (ZoneStatMinute stat : sum.stats()) {
            Map<ZoneDirection, Long> neighbors = neighborsByZone.getOrDefault(stat.getZoneId(), Map.of());
            // 이 구역의 방향별 유입·유출을 복사해 두고, 지역 안으로 이어지는 방향만 0으로 바꾼다
            int[] in = {stat.getInN(), stat.getInE(), stat.getInS(), stat.getInW()};
            int[] out = {stat.getOutN(), stat.getOutE(), stat.getOutS(), stat.getOutW()};
            for (ZoneDirection direction : ZoneDirection.values()) {
                // 그 방향의 이웃이 같은 지역의 구역이면 지역 입장에서는 내부 이동이라 유입·유출 어느 쪽에도 세지 않는다(이웃이 없으면 null이라 포함되지 않는다)
                if (targetZoneIds.contains(neighbors.get(direction))) {
                    in[direction.ordinal()] = 0;
                    out[direction.ordinal()] = 0;
                }
            }
            add(inByDirection, in[0], in[1], in[2], in[3]);
            add(outByDirection, out[0], out[1], out[2], out[3]);
        }
        // 밀집도를 대상(구역/지역)별 기준값과 비교해 혼잡 단계(1~3)로 바꾼다
        int level = levelService.judgeLevel(target.levelTarget(), density).getLevel();
        return new DashboardSummaryResponse(
                // 1분 통계 시각(한국 시간 LocalDateTime)에 +09:00 오프셋을 붙여 응답한다
                sum.statAt().atOffset(KST_OFFSET),
                sum.countAvg(),
                currentCount,
                density,
                level,
                total(inByDirection),
                total(outByDirection),
                mainDirection(outByDirection),
                todayEventCount,
                cameraCount,
                runningCameraCount,
                todayMaxCount);
    }

    // 합산에 들어간 구역들의 방향별 이웃(구역 번호 → 방향 → 이웃 구역 번호)
    private Map<Long, Map<ZoneDirection, Long>> findNeighbors(List<ZoneStatMinute> stats) {
        // 합산에 들어간 구역만 조회 대상으로 삼는다
        List<Long> zoneIds = stats.stream().map(ZoneStatMinute::getZoneId).toList();
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        for (ZoneNeighbor row : zoneNeighborRepository.findByZoneIdIn(zoneIds)) {
            result.computeIfAbsent(row.getZoneId(), id -> new EnumMap<>(ZoneDirection.class))
                    .put(row.getDirection(), row.getNeighborId());
        }
        return result;
    }

    /** 한 구역의 방향별 값을 누적 배열(N·E·S·W 순서)에 더한다 */
    private void add(int[] byDirection, int north, int east, int south, int west) {
        byDirection[0] += north;
        byDirection[1] += east;
        byDirection[2] += south;
        byDirection[3] += west;
    }

    /** 네 방향의 합 */
    private int total(int[] byDirection) {
        int total = 0;
        for (int count : byDirection) {
            total += count;
        }
        return total;
    }

    // 유출이 가장 많은 방향, 같으면 N·E·S·W 순서의 앞쪽. 유출이 하나도 없으면 방향이 없다(null)
    private String mainDirection(int[] outByDirection) {
        int best = 0;
        for (int i = 1; i < outByDirection.length; i++) {
            // 엄격히 클 때만 바꾸므로 같을 때는 앞의 방향(N·E·S·W 순)이 남는다
            if (outByDirection[i] > outByDirection[best]) {
                best = i;
            }
        }
        // 최댓값이 0이면 유출이 없다는 뜻이라 방향도 없다
        return outByDirection[best] == 0 ? null : DIRECTIONS.get(best);
    }

    /**
     * @param activeZones 현재 값·캠 수에 쓰는 삭제되지 않은 구역
     * @param allZoneIds 오늘 기록에 쓰는 구역 id(삭제 포함)
     */
    // 대상(구역 하나 또는 지역) 정보를 한 번에 들고 다니는 내부용 값 묶음. levelTarget은 혼잡 단계 판정의 기준 대상이다
    private record Target(LevelTarget levelTarget, List<Zone> activeZones, List<Long> allZoneIds, boolean isZone) {}
}
