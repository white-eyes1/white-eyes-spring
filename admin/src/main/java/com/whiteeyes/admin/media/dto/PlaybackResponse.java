package com.whiteeyes.admin.media.dto;

import java.time.OffsetDateTime;

/**
 * 다시보기 녹화 찾기 응답(FNC-A-043). 파일 경로는 넣지 않고 녹화 번호로 가리키는 주소만 준다(sec-004).
 *
 * @param seekSec 재생 시작 위치(초) = 찾은 시각 − 녹화 시작 시각. latest 요청이면 0
 */
public record PlaybackResponse(
        // 찾은 녹화 번호와, 영상·좌표 파일을 받는 주소(관리자 앱 컨텍스트 경로 포함).
        Long recordingId,
        String videoUrl,
        String boxesUrl,
        double seekSec,
        // 녹화의 시작·종료 시각(한국 오프셋 포함). 진행 중 녹화는 endedAt이 null이다.
        OffsetDateTime startedAt,
        OffsetDateTime endedAt) {}
