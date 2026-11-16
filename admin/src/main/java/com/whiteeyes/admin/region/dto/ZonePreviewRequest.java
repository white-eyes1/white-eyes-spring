package com.whiteeyes.admin.region.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** boundary는 GeoJSON Polygon. 모양 검증은 service가 한다(INVALID_POLYGON). */
// 미리보기용 다각형(GeoJSON Polygon). 필수이며, 도형 검사는 service가 한다
public record ZonePreviewRequest(@NotNull(message = "필수입니다.") JsonNode boundary) {}
