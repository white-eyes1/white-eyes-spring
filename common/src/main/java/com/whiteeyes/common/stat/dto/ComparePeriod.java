package com.whiteeyes.common.stat.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 기간 비교의 한 기간. 날짜만 받는 조회 조건이라 양끝 날짜를 포함한다(conventions.md '기간 조회').
 */
public record ComparePeriod(LocalDate from, LocalDate to) {

    /** 조회 시작 시각(from 0시, 포함) */
    public LocalDateTime startAt() {
        return from.atStartOfDay();
    }

    /** 조회 끝 시각(to 다음 날 0시, 미포함) */
    public LocalDateTime endAt() {
        return to.plusDays(1).atStartOfDay();
    }

    public ComparePeriod minusDays(int days) {
        return new ComparePeriod(from.minusDays(days), to.minusDays(days));
    }
}
