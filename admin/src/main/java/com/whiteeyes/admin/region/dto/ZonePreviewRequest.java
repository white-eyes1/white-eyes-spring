package com.whiteeyes.admin.region.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** boundary는 GeoJSON Polygon. 모양 검증은 service가 한다(INVALID_POLYGON). */
public record ZonePreviewRequest(@NotNull(message = "필수입니다.") JsonNode boundary) {}
