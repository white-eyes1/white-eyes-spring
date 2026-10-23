package com.whiteeyes.common.stat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatDailyId;

public interface ZoneStatDailyRepository extends JpaRepository<ZoneStatDaily, ZoneStatDailyId> {

    /** 그 구역의 행이 하나라도 있는지(구역 삭제 FNC-A-017 처리 순서 2의 이력 확인). */
    boolean existsByZoneId(Long zoneId);
}
