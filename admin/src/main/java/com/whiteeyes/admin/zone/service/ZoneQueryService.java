package com.whiteeyes.admin.zone.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.zone.entity.Zone;
import com.whiteeyes.common.zone.repository.ZoneRepository;

// TODO(#10): 구역 등록(FNC-A-014)에서 ZoneService가 생기면 그쪽으로 옮긴다
@Service
public class ZoneQueryService {

    private final ZoneRepository zoneRepository;

    public ZoneQueryService(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /**
     * 구역의 현재 면적(㎡)을 zoneId → 면적으로 돌려준다(FNC-A-028 밀집도 계산). 없는 구역은 빠진다.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> findAreasByZoneIds(Set<Long> zoneIds) {
        return zoneRepository.findAllById(zoneIds).stream()
                .collect(Collectors.toMap(Zone::getId, Zone::getAreaM2));
    }
}
