package com.whiteeyes.admin.zone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** boundary는 GeoJSON Polygon. 모양 검증은 service가 한다(INVALID_POLYGON). */
public record ZoneCreateRequest(
        @NotBlank(message = "필수입니다.") @Size(max = 50, message = "50자 이하로 입력해 주세요.") String name,
        @NotNull(message = "필수입니다.") JsonNode boundary) {}
