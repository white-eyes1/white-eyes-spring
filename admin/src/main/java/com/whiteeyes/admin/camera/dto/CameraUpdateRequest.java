package com.whiteeyes.admin.camera.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.camera.entity.CameraSourceType;

/**
 * 보낸 필드만 바꾼다. 셋 다 비면 service가 400 INVALID_INPUT으로 거부한다.
 * sourceUri의 입력 방식별 규칙은 service가 (바뀐 뒤의) 입력 방식과 함께 확인한다. 여기서는 비었는지와 길이만 본다.
 */
public record CameraUpdateRequest(
        // 정규식 .*\S.* 는 공백이 아닌 글자가 하나 이상 있어야 통과한다. 보내지 않은(null) 필드는 검사하지 않아 부분 수정이 된다.
        @Pattern(regexp = ".*\\S.*", message = "비어 있을 수 없습니다.") @Size(max = 50, message = "50자 이하로 입력해 주세요.")
                String name,
        CameraSourceType sourceType,
        @Pattern(regexp = ".*\\S.*", message = "비어 있을 수 없습니다.") @Size(max = 255, message = "255자 이하로 입력해 주세요.")
                String sourceUri) {}
