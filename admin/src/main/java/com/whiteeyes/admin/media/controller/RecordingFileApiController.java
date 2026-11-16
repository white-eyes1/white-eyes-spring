package com.whiteeyes.admin.media.controller;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.media.service.RecordingFileService;

/**
 * 녹화 파일 전송(FNC-A-044). 본문 없이 X-Accel-Redirect 헤더만 돌려주고, 파일은 nginx가 Range 요청까지 처리해 보낸다.
 * 영상은 mp4, 좌표 파일은 gzip JSON으로 보내는 헤더는 nginx 설정이 붙인다.
 */
@RestController
@RequiredArgsConstructor
public class RecordingFileApiController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // nginx가 이 헤더를 보면 응답 본문 대신 그 내부 주소의 파일을 대신 내려 준다(Range 요청 포함). 앱이 큰 영상을 직접 읽지 않으려는 구조다.
    private static final String ACCEL_REDIRECT = "X-Accel-Redirect";

    // 권한·존재·보관 기간 확인과 내부 주소 계산은 서비스가 한다.
    private final RecordingFileService recordingFileService;

    /** 녹화 영상(mp4) 전송. 본문 없이 X-Accel-Redirect 헤더만 응답한다. */
    @GetMapping("/api/v1/recordings/{id}/video")
    public ResponseEntity<Void> video(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .header(ACCEL_REDIRECT, recordingFileService.videoRedirect(id, LocalDateTime.now(KST)))
                .build();
    }

    /** 녹화의 사람 좌표 파일(gzip JSON) 전송. 영상과 같은 방식이다. */
    @GetMapping("/api/v1/recordings/{id}/boxes")
    public ResponseEntity<Void> boxes(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .header(ACCEL_REDIRECT, recordingFileService.boxesRedirect(id, LocalDateTime.now(KST)))
                .build();
    }
}
