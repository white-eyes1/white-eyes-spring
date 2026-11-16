package com.whiteeyes.common.stat.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 기간 비교의 한 기간. 날짜만 받는 조회 조건이라 양끝 날짜를 포함한다(conventions.md '기간 조회').
 */
public record ComparePeriod(LocalDate from, LocalDate to) {

    /** 조회 시작 시각(from 0시, 포함) */
    public LocalDateTime startAt() {
        // to는 포함이므로, 끝은 "to 다음 날 0시 미만"으로 바꿔서 쿼리의 반열린 구간 [시작, 끝)과 맞춘다
        return from.atStartOfDay();
    }

    /** 조회 끝 시각(to 다음 날 0시, 미포함) */
    public LocalDateTime endAt() {
        // 마지막 날 23:59:59.999까지 빠짐없이 포함시키려고 다음 날 0시를 미포함 끝으로 쓴다
        return to.plusDays(1).atStartOfDay();
    }

    /** 양끝을 같은 일수만큼 앞으로 옮긴 기간. 프리셋으로 기준 기간을 만들 때 쓴다 */
    public ComparePeriod minusDays(int days) {
        // 두 날짜를 같은 일수만큼 옮기므로 기간 길이는 변하지 않는다
        return new ComparePeriod(from.minusDays(days), to.minusDays(days));
    }
}
