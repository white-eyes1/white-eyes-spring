package com.whiteeyes.admin.media.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// endedAt은 더미 캠이면 없고 라이브 캠이면 필수다. 캠의 입력 방식에 따라 달라서 서비스에서 확인한다
/**
 * 분석 서버의 녹화 등록 요청 본문(FNC-A-026).
 * videoPath(영상)·boxPath(사람 좌표)는 공유 폴더 기준 상대경로이며 규칙은 RecordingPathValidator가 확인한다.
 */
public record RecordingRegisterRequest(
        @NotNull Long cameraId,
        @NotNull OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        @NotBlank @Size(max = 255) String videoPath,
        @NotBlank @Size(max = 255) String boxPath) {}
