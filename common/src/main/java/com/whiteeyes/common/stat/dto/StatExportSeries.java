package com.whiteeyes.common.stat.dto;

/**
 * 통계 엑셀의 대상 한 줄(구역 하나 또는 지역 합산). 엑셀 열 '시·구·동·구역'(FNC-C-023 처리 순서 3)에 쓴다.
 * 이름은 계획을 세울 때 읽어 두므로 엑셀을 쓰는 동안 지역·구역을 다시 읽지 않는다.
 *
 * @param zoneId 구역 번호. 지역 합산이면 null
 * @param sido 시·도 이름
 * @param sigungu 시·군·구 이름. 시·도 합산이면 null
 * @param emd 읍·면·동 이름. 시·군·구까지의 합산이면 null
 * @param zoneName 구역 이름. 지역 합산이면 null
 */
public record StatExportSeries(Long zoneId, String sido, String sigungu, String emd, String zoneName) {
}
