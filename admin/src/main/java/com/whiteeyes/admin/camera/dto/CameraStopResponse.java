package com.whiteeyes.admin.camera.dto;

import com.whiteeyes.common.camera.entity.CameraStatus;

/**
 * 캠 정지 응답(POST /api/v1/manage/cameras/{id}/stop). 분석 서버에 정지를 전달하지 못했어도 앱 쪽은
 * 정지로 저장하므로 status는 늘 STOPPED이고, 전달에 실패했으면 warning에 문구가 들어간다(없으면 null).
 */
public record CameraStopResponse(CameraStatus status, String warning) {}
