package com.whiteeyes.common.stat.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatHourlyId;

public interface ZoneStatHourlyRepository extends JpaRepository<ZoneStatHourly, ZoneStatHourlyId> {

    /** from 이상 to 미만 시간별 통계(전 구역). 일별 통계가 하루치를 묶을 때 쓴다(FNC-A-030). */
    List<ZoneStatHourly> findByStatAtGreaterThanEqualAndStatAtLessThan(LocalDateTime from, LocalDateTime to);
}
