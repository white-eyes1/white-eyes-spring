package com.whiteeyes.admin.region.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.whiteeyes.admin.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인(캠)이 구역을 확인할 때 부르는 구역 도메인의 공개 service. */
@Service
@RequiredArgsConstructor
public class ZoneService {

    private final ZoneRepository zoneRepository;

    /** 구역이 있고 삭제되지 않았는지 확인한다. 아니면 404 ZONE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public void requireActiveZone(Long zoneId) {
        if (!zoneRepository.existsByIdAndDeletedAtIsNull(zoneId)) {
            throw new ZoneNotFoundException(zoneId);
        }
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
