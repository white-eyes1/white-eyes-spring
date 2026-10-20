package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** sourceUri의 입력 방식별 규칙은 service가 확인한다(처리 순서 3). 여기서는 비었는지와 길이만 본다. */
public record CameraCreateRequest(
        @NotNull(message = "필수입니다.") Long zoneId,
        @NotBlank(message = "필수입니다.") @Size(max = 50, message = "50자 이하로 입력해 주세요.") String name,
        @NotNull(message = "필수입니다.") CameraSourceType sourceType,
        @NotBlank(message = "필수입니다.") @Size(max = 255, message = "255자 이하로 입력해 주세요.") String sourceUri) {}
