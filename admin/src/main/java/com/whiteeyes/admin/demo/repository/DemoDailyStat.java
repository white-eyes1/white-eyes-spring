package com.whiteeyes.admin.demo.repository;

import java.time.LocalDate;

import com.whiteeyes.common.stat.entity.StatValues;

/** 시연용 일별 통계 한 행(FNC-A-051). 저장할 때 is_demo=1로 넣는다. */
public record DemoDailyStat(long zoneId, LocalDate statDate, StatValues values) {
}
