package com.whiteeyes.common.stat.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.stat.dto.ZoneMinuteStat;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

/** 다른 도메인이 1분 통계를 읽을 때 부르는 stat 도메인의 공개 서비스(package-structure.md 3-2). 캠 목록(FNC-A-022)이 쓴다. */
@Service
@RequiredArgsConstructor
public class ZoneStatQueryService {

    // 1분 통계 테이블 접근. 다른 도메인이 저장소에 직접 접근하지 않도록 이 서비스가 공개 창구 역할을 한다
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;

    /**
     * 구역들의 from 이후(포함) 1분 통계를 구역별로, 시각이 오래된 것부터 돌려준다. 구역 수와 관계없이 쿼리 한 번이다.
     * 통계가 없는 구역은 결과에 키가 없다.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ZoneMinuteStat>> findRecentByZone(Collection<Long> zoneIds, LocalDateTime from) {
        // 빈 목록으로 IN 쿼리를 보내면 DB에 따라 오류가 날 수 있어 미리 끝낸다
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        // 구역 목록을 한 번에 넘겨 쿼리 한 번으로 읽는다(구역마다 조회하는 N+1 방지)
        return zoneStatMinuteRepository.findByZoneIdInAndStatAtGreaterThanEqual(zoneIds, from).stream()
                // 구역별로 나눈 뒤에도 각 목록이 시각 오름차순이 되도록 먼저 전체를 정렬한다(groupingBy는 순서를 유지한다)
                .sorted(Comparator.comparing(ZoneStatMinute::getStatAt))
                // 엔티티를 그대로 내보내지 않고 화면이 쓰는 값만 담은 레코드로 바꾼다
                .map(stat -> new ZoneMinuteStat(
                        stat.getZoneId(), stat.getStatAt(), stat.getCountAvg(), stat.getDensity(), stat.getSampleSec()))
                // 구역 번호별로 묶는다. 통계가 없는 구역은 키 자체가 생기지 않는다
                .collect(Collectors.groupingBy(ZoneMinuteStat::zoneId));
    }

    /**
     * 구역들의 from 이후(포함) 마지막 판정 가능 분(sample_sec >= minSampleSec)의 stat_at을 구역별로 돌려준다. 쿼리 한 번이다.
     * from 이후에 판정 가능한 행이 없는 구역은 결과에 키가 없다. 결과 끊김 종료(FNC-A-033 5번)가 쓴다.
     */
    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> findLastJudgeableMinuteByZone(
            Collection<Long> zoneIds, LocalDateTime from, int minSampleSec) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        // 구역별 마지막 판정 가능 분을 group by로 한 번에 읽는다
        return zoneStatMinuteRepository.findLastStatAtByZoneIds(zoneIds, from, minSampleSec).stream()
                // 구역 번호 → 마지막 판정 가능 분(stat_at). 구역당 한 행만 오므로 키 충돌은 없다
                .collect(Collectors.toMap(
                        ZoneStatMinuteRepository.ZoneLastStatAt::getZoneId,
                        ZoneStatMinuteRepository.ZoneLastStatAt::getStatAt));
    }
}
