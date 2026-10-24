package com.whiteeyes.common.stat.dto;

import java.util.List;

/** GET /api/v1/stats/series 응답(FNC-C-012). items는 시각 순이고, 조회 기간의 모든 구간을 빠짐없이 담는다. */
public record StatSeriesResponse(List<StatSeriesPoint> items) {
}
