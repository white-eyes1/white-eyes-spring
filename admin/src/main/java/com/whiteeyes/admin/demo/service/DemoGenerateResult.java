package com.whiteeyes.admin.demo.service;

/**
 * 시연용 통계 생성 결과(FNC-A-051 정상 결과).
 *
 * @param zones 데이터를 만든 구역 수
 * @param hourly 넣은 시간별 건수
 * @param daily 넣은 일별 건수
 * @param deleted 먼저 지운 기존 시연용 데이터
 */
public record DemoGenerateResult(int zones, long hourly, long daily, DemoDeleteResult deleted) {
}
