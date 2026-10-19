/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/dto/ResultReceiveRequest.java
 * 관련 이슈: #16(FNC-A-024)
 *
 * [무엇] POST /internal/v1/results 요청 본문 배열의 항목 하나(api/analyzer.md의 JSON 계약)를 그대로 옮긴 요청 DTO.
 *   {"cameraId": 7, "at": "2026-10-12T14:03:05+09:00", "personCount": 42,
 *    "in": {"N": 1, "E": 0, "S": 0, "W": 2}, "out": {…}, "dwellSec": [305.2, 41.0]}
 * [왜 record인가] DTO는 Java record를 기본으로 한다(code-conventions 3-3). 값만 담고 바뀌지 않으며 equals/toString이 자동으로 생긴다.
 * [염두에 둔 것]
 *   - 이름은 '{동작}Request' 규칙(code-conventions 3-2)에 따라 ResultReceiveRequest.
 *   - 모든 칸에 @NotNull: 계약에 선택 항목이 없다. 하나라도 빠지면 형식 오류 → 400(분석 서버는 그 묶음을 버림).
 *   - 숫자는 @PositiveOrZero: 인원·통과 인원·체류 시간이 음수일 수 없으므로 깨진 값을 입구에서 막는다.
 *   - 원시형(int) 대신 Integer: int는 값이 없으면 0으로 채워져 '빠진 값'과 '진짜 0'을 구분할 수 없다. Integer여야 @NotNull이 잡는다.
 */
package com.whiteeyes.admin.stat.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ResultReceiveRequest(
        @NotNull Long cameraId,
        // [해석] OffsetDateTime: 계약의 시각은 ISO-8601 + 오프셋(+09:00) 형식이다(conventions.md '시각 형식').
        //        오프셋까지 받아 두면 어떤 시간대로 와도 서비스에서 정확히 KST로 바꿀 수 있다.
        @NotNull OffsetDateTime at,
        @NotNull @PositiveOrZero Integer personCount,
        // [해석] @Valid: 안쪽 객체(DirectionCount)의 검증 규칙까지 검사하라는 표시. 없으면 {"N": -1}이 그냥 통과한다.
        @NotNull @Valid DirectionCount in,
        @NotNull @Valid DirectionCount out,
        // [해석] 리스트 원소마다 검사(List<@NotNull @PositiveOrZero Double>). [305.2, null]이나 [-3.0]도 400.
        //        빈 리스트 []는 허용: 그 1초 동안 구역을 떠난 사람이 없으면 정상적으로 비어 있다.
        @NotNull List<@NotNull @PositiveOrZero Double> dwellSec) {

    // [해석] in/out의 {"N","E","S","W"} 묶음. 같은 모양이 두 번 나오므로 한 번만 정의해 재사용한다.
    //        이 DTO에서만 쓰는 모양이라 바깥 파일로 빼지 않고 안쪽(nested) record로 뒀다.
    public record DirectionCount(
            // [해석] JSON 키는 계약대로 대문자 한 글자("N")지만, Java 쪽 이름은 north처럼 뜻이 드러나게 짓는다
            //        (code-conventions 2-1 "의미 없는 축약어 금지"). 둘을 이어 주는 것이 @JsonProperty.
            @JsonProperty("N") @NotNull @PositiveOrZero Integer north,
            @JsonProperty("E") @NotNull @PositiveOrZero Integer east,
            @JsonProperty("S") @NotNull @PositiveOrZero Integer south,
            @JsonProperty("W") @NotNull @PositiveOrZero Integer west) {}
}
