package com.whiteeyes.admin.region.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 보낸 필드만 바꾼다. 둘 다 비면 service가 400 INVALID_INPUT으로 거부한다. boundary 모양 검증은 service가 한다(INVALID_POLYGON). */
public record ZoneUpdateRequest(
        @Pattern(regexp = ".*\\S.*", message = "비어 있을 수 없습니다.") @Size(max = 50, message = "50자 이하로 입력해 주세요.") String name,
        JsonNode boundary) {}
