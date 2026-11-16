package com.whiteeyes.admin.region.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** boundary는 GeoJSON Polygon. 모양 검증은 service가 한다(INVALID_POLYGON). */
public record ZoneCreateRequest(
        // 구역 이름: 비어 있거나 공백뿐이면 거부하고 50자까지 허용한다(service가 앞뒤 공백을 다듬어 저장)
        @NotBlank(message = "필수입니다.") @Size(max = 50, message = "50자 이하로 입력해 주세요.") String name,
        // 지도에 그린 다각형(GeoJSON Polygon). 도형이 올바른지는 service가 검사한다
        @NotNull(message = "필수입니다.") JsonNode boundary) {}
