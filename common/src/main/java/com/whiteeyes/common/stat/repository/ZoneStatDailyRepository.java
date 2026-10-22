package com.whiteeyes.common.stat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatDailyId;

public interface ZoneStatDailyRepository extends JpaRepository<ZoneStatDaily, ZoneStatDailyId> {
}
