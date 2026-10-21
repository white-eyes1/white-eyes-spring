package com.whiteeyes.common.stat.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 구역의 1분 통계 한 건에서 화면이 쓰는 값만 뽑은 것(tables.md 'zone_stat_minute').
 *
 * @param zoneId 구역 번호
 * @param statAt 1분 구간 시작 시각(KST)
 * @param countAvg 1분 평균 인원
 * @param density 평균 밀집도(명/㎡)
 */
public record ZoneMinuteStat(Long zoneId, LocalDateTime statAt, BigDecimal countAvg, BigDecimal density) {}
