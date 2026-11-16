package com.whiteeyes.admin.camera.dto;

/**
 * 캠 삭제 응답(DELETE /api/v1/manage/cameras/{id}). 가동한 적이 없으면 실제로 지우고(HARD),
 * 가동 이력이 있으면 삭제 표시만 한다(SOFT).
 */
public record CameraDeleteResponse(Mode mode) {

    public enum Mode {
        // 가동한 적이 없어 DB 행을 실제로 지웠다.
        HARD,
        // 가동 이력이 있어 행은 남기고 삭제 시각만 표시했다(녹화·통계 이력 보존).
        SOFT
    }
}
