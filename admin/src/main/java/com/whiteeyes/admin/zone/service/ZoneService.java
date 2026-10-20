package com.whiteeyes.admin.zone.service;

import com.whiteeyes.admin.zone.exception.ZoneNotFoundException;
import com.whiteeyes.common.zone.repository.ZoneRepository;
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
}
