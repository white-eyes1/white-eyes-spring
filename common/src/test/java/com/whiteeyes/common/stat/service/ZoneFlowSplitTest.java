package com.whiteeyes.common.stat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.whiteeyes.common.stat.service.ZoneFlowService.Segment;
import com.whiteeyes.common.stat.service.ZoneFlowService.Source;

/** 구역 간 이동 흐름(FNC-C-010 처리 순서 2)의 원본 고르기: 통째 하루는 일별, 통째 시간은 시간별, 나머지는 1분 통계 */
class ZoneFlowSplitTest {

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2099, 1, day, hour, minute);
    }

    @Test
    @DisplayName("FNC-C-010 10:30 ~ 이틀 뒤 12:15 → 1분·시간별·일별·시간별·1분")
    void mixed() {
        assertThat(ZoneFlowService.split(at(1, 10, 30), at(3, 12, 15))).containsExactly(
                new Segment(Source.MINUTE, at(1, 10, 30), at(1, 11, 0)),
                new Segment(Source.HOUR, at(1, 11, 0), at(2, 0, 0)),
                new Segment(Source.DAY, at(2, 0, 0), at(3, 0, 0)),
                new Segment(Source.HOUR, at(3, 0, 0), at(3, 12, 0)),
                new Segment(Source.MINUTE, at(3, 12, 0), at(3, 12, 15)));
    }

    @Test
    @DisplayName("FNC-C-010 하루가 통째로 들어가지 않으면 일별을 쓰지 않는다(22시 ~ 다음 날 3시)")
    void acrossMidnightWithoutWholeDay() {
        assertThat(ZoneFlowService.split(at(1, 22, 0), at(2, 3, 0))).containsExactly(
                new Segment(Source.HOUR, at(1, 22, 0), at(2, 0, 0)),
                new Segment(Source.HOUR, at(2, 0, 0), at(2, 3, 0)));
    }

    @Test
    @DisplayName("FNC-C-010 정확히 며칠이면 일별만, 1시간 미만이면 1분만")
    void wholeDaysOrMinutesOnly() {
        assertThat(ZoneFlowService.split(at(1, 0, 0), at(3, 0, 0)))
                .containsExactly(new Segment(Source.DAY, at(1, 0, 0), at(3, 0, 0)));
        assertThat(ZoneFlowService.split(at(1, 10, 0), at(1, 10, 40)))
                .containsExactly(new Segment(Source.MINUTE, at(1, 10, 0), at(1, 10, 40)));
        assertThat(ZoneFlowService.split(at(1, 10, 30), at(1, 10, 40)))
                .containsExactly(new Segment(Source.MINUTE, at(1, 10, 30), at(1, 10, 40)));
        // 기본 기간(최근 1시간)이 정시에 걸치면 1분 두 구간
        assertThat(ZoneFlowService.split(at(1, 9, 20), at(1, 10, 20))).containsExactly(
                new Segment(Source.MINUTE, at(1, 9, 20), at(1, 10, 0)),
                new Segment(Source.MINUTE, at(1, 10, 0), at(1, 10, 20)));
    }
}
