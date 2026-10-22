package com.whiteeyes.admin.media.dto;

import java.time.OffsetDateTime;

/**
 * 다시보기 녹화 찾기 응답(FNC-A-043). 파일 경로는 넣지 않고 녹화 번호로 가리키는 주소만 준다(sec-004).
 *
 * @param seekSec 재생 시작 위치(초) = 찾은 시각 − 녹화 시작 시각. latest 요청이면 0
 */
public record PlaybackResponse(
        Long recordingId,
        String videoUrl,
        String boxesUrl,
        double seekSec,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt) {}
