package com.whiteeyes.tools.demo.repository;

import java.time.LocalDateTime;

import com.whiteeyes.common.stat.entity.StatValues;

/** 시연용 시간별 통계 한 행(FNC-A-051). 저장할 때 is_demo=1로 넣는다. */
// zoneId: 구역 번호, statAt: 통계 시각(KST 정시), values: 인원·이동·체류 등 통계 값 묶음.
public record DemoHourlyStat(long zoneId, LocalDateTime statAt, StatValues values) {}
