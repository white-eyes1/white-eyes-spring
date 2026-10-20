package com.whiteeyes.common.zone.service;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.zone.entity.Zone;
import com.whiteeyes.common.zone.repository.ZoneRepository;

/**
 * 다른 도메인(지도 현황 등)에 구역을 내주는 조회 서비스.
 */
// TODO(#8): 지역·구역 목록(FNC-C-008)에서 구역 서비스가 생기면 그쪽으로 옮긴다.
// 이름을 ZoneQueryService로 하지 않은 이유: #18의 admin ZoneQueryService와 빈 이름이 같아 함께 머지되면 앱이 뜨지 않는다
@Service
public class ZoneLookupService {

    private final ZoneRepository zoneRepository;

    public ZoneLookupService(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /** 읍·면·동들에 속한 삭제되지 않은 구역(삭제된 구역은 목록·지도에서 뺀다, tables.md zone). */
    @Transactional(readOnly = true)
    public List<Zone> findZonesInDongs(Collection<Long> dongIds) {
        return zoneRepository.findByDongIdInAndDeletedAtIsNull(dongIds);
    }
}
