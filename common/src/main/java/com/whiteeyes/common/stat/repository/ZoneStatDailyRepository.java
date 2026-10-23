package com.whiteeyes.common.stat.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatDailyId;

public interface ZoneStatDailyRepository extends JpaRepository<ZoneStatDaily, ZoneStatDailyId> {

    /** 구역들의 from 이상 to 미만 날짜의 일별 통계. 통계 시계열 1d(FNC-C-012)가 구역 수와 관계없이 한 번에 읽는다. */
    List<ZoneStatDaily> findByZoneIdInAndStatDateGreaterThanEqualAndStatDateLessThan(
            Collection<Long> zoneIds, LocalDate from, LocalDate to);

    /** 그 구역의 행이 하나라도 있는지(구역 삭제 FNC-A-017 처리 순서 2의 이력 확인). */
    boolean existsByZoneId(Long zoneId);
}
