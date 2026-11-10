package com.whiteeyes.admin.event.dto;

import java.time.OffsetDateTime;

/**
 * 이벤트 엑셀의 한 행(FNC-A-039). 목록 행에 처리 정보가 더해진다.
 *
 * @param sido 시·도 이름. 구역의 지역을 알 수 없으면 null
 * @param sigungu 시·군·구 이름
 * @param emd 읍·면·동 이름
 * @param actionCode 조치 항목 코드. 조치완료가 아니면 null
 * @param actionNote 조치 메모
 * @param handlerLoginId 마지막으로 처리한 관리자의 아이디. 처리 전이면 null
 * @param handledAt 마지막 처리 시각
 */
public record EventExportRow(
        EventListItem item,
        String sido,
        String sigungu,
        String emd,
        String actionCode,
        String actionNote,
        String handlerLoginId,
        OffsetDateTime handledAt) {}
