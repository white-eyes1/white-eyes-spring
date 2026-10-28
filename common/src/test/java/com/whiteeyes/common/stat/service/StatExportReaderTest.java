package com.whiteeyes.common.stat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindException;

import com.whiteeyes.common.stat.dto.StatExportPlan;
import com.whiteeyes.common.stat.dto.StatExportRequest;
import com.whiteeyes.common.stat.dto.StatExportScope;
import com.whiteeyes.common.stat.dto.StatExportSeries;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatSeriesResponse;

/** 통계 엑셀 다운로드(FNC-C-023) 값 읽기: 나눠 읽기 */
class StatExportReaderTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 1, 0, 0);

    private final StatSeriesService statSeriesService = mock(StatSeriesService.class);

    @Test
    @DisplayName("FNC-C-023 구역별 줄은 기준(구역 수 × 시각 수) 안에서 시각을 잘라 모든 구역을 읽고, 시각 순으로 묶어 계획의 줄 순서대로 넘긴다")
    void readPerZone_windowsTime() throws BindException {
        // 1시간 5개 시각(00~04시), 구역 3개, 기준 6행 → 한 번에 시각 2개
        StatExportRequest request = request(StatExportScope.ALL, "2099-01-01T00:00", "2099-01-01T05:00");
        List<StatExportSeries> series = List.of(zone(5), zone(3), zone(9));
        List<List<Object>> calls = new ArrayList<>();
        when(statSeriesService.findZoneSeries(anyList(), eq(StatInterval.HOUR), any(), any()))
                .thenAnswer(invocation -> {
                    List<Long> zoneIds = invocation.getArgument(0);
                    LocalDateTime from = invocation.getArgument(2);
                    LocalDateTime to = invocation.getArgument(3);
                    calls.add(List.of(zoneIds, from, to));
                    Map<Long, List<StatSeriesPoint>> result = new LinkedHashMap<>();
                    // 받은 순서와 다르게 돌려줘도 계획 순서대로 넘기는지 보려고 거꾸로 넣는다
                    for (int i = zoneIds.size() - 1; i >= 0; i--) {
                        List<StatSeriesPoint> points = new ArrayList<>();
                        for (LocalDateTime at = from; at.isBefore(to); at = at.plusHours(1)) {
                            points.add(point(at));
                        }
                        result.put(zoneIds.get(i), points);
                    }
                    return result;
                });

        List<String> rows = new ArrayList<>();
        reader(6).read(new StatExportPlan(request, "전체", series, 3, 15),
                (line, point) -> rows.add(point.at().getHour() + "@" + line.zoneId()));

        assertThat(calls).containsExactly(
                List.of(List.of(5L, 3L, 9L), at(0), at(2)),
                List.of(List.of(5L, 3L, 9L), at(2), at(4)),
                List.of(List.of(5L, 3L, 9L), at(4), at(5)));
        assertThat(rows).containsExactly(
                "0@5", "0@3", "0@9", "1@5", "1@3", "1@9", "2@5", "2@3", "2@9", "3@5", "3@3", "3@9", "4@5", "4@3", "4@9");
    }

    @Test
    @DisplayName("FNC-C-023 줄 하나(지역 합산)는 기준 ÷ 원본 구역 수만큼 시각을 잘라 통계 그래프 계산을 부르고 이어 붙인다")
    void readSingle_splitsPeriod() throws BindException {
        // 1시간 7개 시각(10:30부터라 10시 구간부터), 원본 구역 2개, 기준 6행 → 한 토막 3시간
        StatExportRequest request = request(StatExportScope.REGION, "2099-01-01T10:30", "2099-01-01T17:00");
        StatExportSeries line = new StatExportSeries(null, "서울특별시", "종로구", null, null);
        List<StatSeriesRequest> calls = new ArrayList<>();
        when(statSeriesService.findSeries(any())).thenAnswer(invocation -> {
            StatSeriesRequest call = invocation.getArgument(0);
            calls.add(call);
            List<StatSeriesPoint> items = new ArrayList<>();
            for (LocalDateTime at = call.from(); at.isBefore(call.to()); at = at.plusHours(1)) {
                items.add(point(at));
            }
            return new StatSeriesResponse(items);
        });

        List<Integer> hours = new ArrayList<>();
        reader(6).read(new StatExportPlan(request, "종로구", List.of(line), 2, 7),
                (series, point) -> hours.add(point.at().getHour()));

        assertThat(calls).extracting(StatSeriesRequest::from, StatSeriesRequest::to).containsExactly(
                tuple(at(10), at(13)),
                tuple(at(13), at(16)),
                tuple(at(16), at(17)));
        assertThat(calls).allSatisfy(call -> assertThat(call.targetId()).isEqualTo(7L));
        assertThat(hours).containsExactly(10, 11, 12, 13, 14, 15, 16);
    }

    @Test
    @DisplayName("FNC-C-023 줄이 없으면(구역 없는 지역) 아무것도 읽지 않는다")
    void readPerZone_noSeries() throws BindException {
        StatExportRequest request = StatExportRequest.parse(
                StatExportScope.REGION, 7L, "1h", "2099-01-01T00:00", "2099-01-01T02:00", List.of("COUNT"), false);
        List<StatExportSeries> rows = new ArrayList<>();

        reader(5).read(new StatExportPlan(request, "종로구", List.of(), 0, 0), (line, point) -> rows.add(line));

        assertThat(rows).isEmpty();
    }

    private StatExportReader reader(int rowBudget) {
        return new StatExportReader(null, null, null, statSeriesService, rowBudget);
    }

    private static StatExportRequest request(StatExportScope scope, String from, String to) throws BindException {
        Long targetId = scope == StatExportScope.ALL ? null : 7L;
        // 줄 하나로 읽는 경우(지역 합산)는 지역에서 합산을 고른 것
        return StatExportRequest.parse(scope, targetId, "1h", from, to, List.of("COUNT"), scope == StatExportScope.REGION);
    }

    private static StatExportSeries zone(long zoneId) {
        return new StatExportSeries(zoneId, "서울특별시", "종로구", "사직동", "구역 " + zoneId);
    }

    private static StatSeriesPoint point(LocalDateTime at) {
        return StatSeriesPoint.empty(at.atOffset(ZoneOffset.ofHours(9)));
    }

    private static LocalDateTime at(int hour) {
        return T0.withHour(hour);
    }
}
