package com.whiteeyes.common.stat.service;

import com.whiteeyes.common.stat.dto.ZoneMinuteStat;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인이 1분 통계를 읽을 때 부르는 stat 도메인의 공개 서비스(package-structure.md 3-2). 캠 목록(FNC-A-022)이 쓴다. */
@Service
@RequiredArgsConstructor
public class ZoneStatQueryService {

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;

    /**
     * 구역들의 from 이후(포함) 1분 통계를 구역별로, 시각이 오래된 것부터 돌려준다. 구역 수와 관계없이 쿼리 한 번이다.
     * 통계가 없는 구역은 결과에 키가 없다.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ZoneMinuteStat>> findRecentByZone(Collection<Long> zoneIds, LocalDateTime from) {
        if (zoneIds.isEmpty()) {
            return Map.of();
        }
        return zoneStatMinuteRepository.findByZoneIdInAndStatAtGreaterThanEqual(zoneIds, from).stream()
                .sorted(Comparator.comparing(ZoneStatMinute::getStatAt))
                .map(stat -> new ZoneMinuteStat(stat.getZoneId(), stat.getStatAt(), stat.getCountAvg(), stat.getDensity(), stat.getSampleSec()))
                .collect(Collectors.groupingBy(ZoneMinuteStat::zoneId));
    }
}
