package com.whiteeyes.common.stat.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatHourlyId;

public interface ZoneStatHourlyRepository extends JpaRepository<ZoneStatHourly, ZoneStatHourlyId> {

    /** 구역들의 from 이상 to 미만 시간별 통계. 통계 시계열 1h(FNC-C-012)가 구역 수와 관계없이 한 번에 읽는다. */
    List<ZoneStatHourly> findByZoneIdInAndStatAtGreaterThanEqualAndStatAtLessThan(
            Collection<Long> zoneIds, LocalDateTime from, LocalDateTime to);

    /** from 이상 to 미만 시간별 통계(전 구역). 일별 통계가 하루치를 묶을 때 쓴다(FNC-A-030). */
    List<ZoneStatHourly> findByStatAtGreaterThanEqualAndStatAtLessThan(LocalDateTime from, LocalDateTime to);
}
