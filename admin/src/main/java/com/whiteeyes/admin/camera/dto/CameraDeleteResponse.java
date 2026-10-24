package com.whiteeyes.admin.camera.dto;

/**
 * 캠 삭제 응답(DELETE /api/v1/manage/cameras/{id}). 가동한 적이 없으면 실제로 지우고(HARD),
 * 가동 이력이 있으면 삭제 표시만 한다(SOFT).
 */
public record CameraDeleteResponse(Mode mode) {

    public enum Mode {
        HARD,
        SOFT
    }
}
