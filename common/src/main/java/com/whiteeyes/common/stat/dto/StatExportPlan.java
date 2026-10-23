package com.whiteeyes.common.stat.dto;

import java.util.List;

/**
 * 통계 엑셀을 쓰기 전에 정한 것(FNC-C-023 처리 순서 1). 대상 확인·행 수 확인을 마친 뒤 만든다.
 *
 * @param targetName 대상 이름(구역·지역 이름, 전체면 '전체'). 파일 이름·작업 기록에 쓴다
 * @param series 시트마다 쓸 대상 줄(이 순서대로). 구역·지역 합산은 1개, 지역 펼침·전체는 삭제되지 않은 구역마다
 * @param sourceZoneCount 원본을 읽을 구역 수. 지역 합산은 삭제된 구역까지 센다(나눠 읽는 크기에 쓴다)
 * @param rows 한 시트의 행 수(대상 줄 수 × 시각 수)
 */
public record StatExportPlan(
        StatExportRequest request,
        String targetName,
        List<StatExportSeries> series,
        int sourceZoneCount,
        long rows) {

    /** 대상 줄마다 구역 시계열을 따로 읽는지(지역 펼침·전체). 아니면 줄 하나를 통계 그래프와 같은 방법으로 읽는다. */
    public boolean perZone() {
        return request.scope() == StatExportScope.ALL || request.expand();
    }
}
