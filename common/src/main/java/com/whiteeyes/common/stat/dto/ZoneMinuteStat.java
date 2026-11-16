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
 * @param sampleSec 그 1분에 분석 결과가 들어온 초(0이면 통계 그래프에서 빈 점)
 */
// 엔티티(ZoneStatMinute) 전체를 화면 계층에 노출하지 않으려고 필요한 값만 담은 읽기용 레코드
public record ZoneMinuteStat(
        Long zoneId, LocalDateTime statAt, BigDecimal countAvg, BigDecimal density, int sampleSec) {}
