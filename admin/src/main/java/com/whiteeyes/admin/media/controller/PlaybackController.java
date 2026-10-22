package com.whiteeyes.admin.media.controller;

import com.whiteeyes.admin.media.dto.PlaybackResponse;
import com.whiteeyes.admin.media.service.PlaybackFindService;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 다시보기 녹화 찾기(FNC-A-043). 구역·시각(at) 또는 가장 최근 녹화(latest=true)로 녹화와 재생 시작 위치를 찾는다. */
@RestController
@RequiredArgsConstructor
public class PlaybackController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PlaybackFindService playbackFindService;

    @GetMapping("/api/v1/playback")
    public PlaybackResponse findPlayback(
            @RequestParam("zoneId") Long zoneId,
            @RequestParam(name = "at", required = false) String at,
            @RequestParam(name = "latest", required = false, defaultValue = "false") boolean latest,
            HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now(KST);
        String basePath = request.getContextPath();
        if (latest) {
            return playbackFindService.findLatest(zoneId, now, basePath);
        }
        if (at == null || at.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "at 또는 latest=true가 필요합니다.");
        }
        return playbackFindService.findAt(zoneId, parseAt(at), now, basePath);
    }

    // 오프셋이 있으면 한국 시간으로 바꾸고, 없으면 한국 시간으로 본다. 주소에서 '+'가 공백이 된 경우도 받는다
    private LocalDateTime parseAt(String at) {
        String text = at.trim().replace(' ', '+');
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(KST).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT, "at은 2026-10-12T14:03:00+09:00 형식이어야 합니다.", e);
            }
        }
    }
}
