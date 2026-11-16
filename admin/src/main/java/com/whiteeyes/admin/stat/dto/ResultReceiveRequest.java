package com.whiteeyes.admin.stat.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 분석 서버가 보내는 1초 결과 한 건(FNC-A-024). 캠 하나가 한 초 동안 센 인원·방향별 출입·체류 시간이다. */
public record ResultReceiveRequest(
        // 결과를 만든 캠 번호. 수신 service가 이 캠이 속한 구역으로 바꿔 쓴다
        @NotNull Long cameraId,
        // 그 초의 시각(UTC 오프셋 포함). 수신 service가 KST 초 단위로 바꿔 분 구간을 정한다
        @NotNull OffsetDateTime at,
        // 그 초의 현재 인원(분석 서버가 이미 반올림한 정수, 0 이상)
        @NotNull @PositiveOrZero Integer personCount,
        // 그 초에 들어온 사람 수(방향별)
        @NotNull @Valid DirectionCount in,
        // 그 초에 나간 사람 수(방향별)
        @NotNull @Valid DirectionCount out,
        // 체류 시간(초) 목록. 1분 통계에서 합계(dwell_sum_sec)와 건수(dwell_count)로 쓰인다. 비어 있을 수 있다
        @NotNull List<@NotNull @PositiveOrZero Double> dwellSec) {

    /** 방향별 인원. JSON 키는 대문자 N·E·S·W이고, 자바 쪽에서는 north·east·south·west로 읽는다. */
    public record DirectionCount(
            // 아래 네 값은 모두 필수이며 0 이상이어야 한다(검증 실패 시 요청 전체가 400)
            @JsonProperty("N") @NotNull @PositiveOrZero Integer north,
            @JsonProperty("E") @NotNull @PositiveOrZero Integer east,
            @JsonProperty("S") @NotNull @PositiveOrZero Integer south,
            @JsonProperty("W") @NotNull @PositiveOrZero Integer west) {}
}
