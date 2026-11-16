package com.whiteeyes.admin.region.dto;

import java.math.BigDecimal;

import com.whiteeyes.common.region.entity.Zone;

/** 다른 도메인(캠 목록, 이벤트 목록)이 구역을 표시할 때 쓰는 구역 요약. 엔티티를 밖으로 내보내지 않는다. */
public record ZoneSummary(Long id, String name, BigDecimal areaM2, Long dongId, boolean deleted) {

    /** 구역 엔티티에서 필요한 값만 뽑아 요약을 만든다. deletedAt이 채워져 있으면 삭제된 구역(deleted=true)이다. */
    public static ZoneSummary from(Zone zone) {
        return new ZoneSummary(
                zone.getId(), zone.getName(), zone.getAreaM2(), zone.getDongId(), zone.getDeletedAt() != null);
    }
}
