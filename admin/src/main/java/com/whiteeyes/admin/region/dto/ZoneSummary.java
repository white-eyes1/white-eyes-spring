package com.whiteeyes.admin.region.dto;

import com.whiteeyes.common.region.entity.Zone;
import java.math.BigDecimal;

/** 다른 도메인(캠 목록)이 구역을 표시할 때 쓰는 구역 요약. 엔티티를 밖으로 내보내지 않는다. */
public record ZoneSummary(Long id, String name, BigDecimal areaM2, Long dongId) {

    public static ZoneSummary from(Zone zone) {
        return new ZoneSummary(zone.getId(), zone.getName(), zone.getAreaM2(), zone.getDongId());
    }
}
