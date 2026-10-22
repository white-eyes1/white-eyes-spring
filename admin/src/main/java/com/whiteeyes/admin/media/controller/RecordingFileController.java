package com.whiteeyes.admin.media.controller;

import com.whiteeyes.admin.media.service.RecordingFileService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 녹화 파일 전송(FNC-A-044). 본문 없이 X-Accel-Redirect 헤더만 돌려주고, 파일은 nginx가 Range 요청까지 처리해 보낸다.
 * 영상은 mp4, 좌표 파일은 gzip JSON으로 보내는 헤더는 nginx 설정이 붙인다.
 */
@RestController
@RequiredArgsConstructor
public class RecordingFileController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ACCEL_REDIRECT = "X-Accel-Redirect";

    private final RecordingFileService recordingFileService;

    @GetMapping("/api/v1/recordings/{id}/video")
    public ResponseEntity<Void> video(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .header(ACCEL_REDIRECT, recordingFileService.videoRedirect(id, LocalDateTime.now(KST)))
                .build();
    }

    @GetMapping("/api/v1/recordings/{id}/boxes")
    public ResponseEntity<Void> boxes(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .header(ACCEL_REDIRECT, recordingFileService.boxesRedirect(id, LocalDateTime.now(KST)))
                .build();
    }
}
