package com.whiteeyes.tools.demo.repository;

import java.time.LocalDate;

import com.whiteeyes.common.stat.entity.StatValues;

/** 시연용 일별 통계 한 행(FNC-A-051). 저장할 때 is_demo=1로 넣는다. */
// zoneId: 구역 번호, statDate: 통계 날짜(KST), values: 인원·이동·체류 등 통계 값 묶음(시간별과 같은 StatValues).
public record DemoDailyStat(long zoneId, LocalDate statDate, StatValues values) {}
