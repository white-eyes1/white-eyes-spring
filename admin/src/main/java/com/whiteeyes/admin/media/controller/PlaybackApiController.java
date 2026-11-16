package com.whiteeyes.admin.media.controller;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.media.dto.PlaybackRangesResponse;
import com.whiteeyes.admin.media.dto.PlaybackResponse;
import com.whiteeyes.admin.media.service.PlaybackFindService;
import com.whiteeyes.admin.media.service.PlaybackRangesService;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/**
 * 다시보기 녹화 찾기(FNC-A-043). 구역·시각(at) 또는 가장 최근 녹화(latest=true)로 녹화와 재생 시작 위치를 찾고,
 * 구간 조회(/ranges)는 시간 범위 안에서 녹화가 있는 구간 목록을 준다.
 */
@RestController
@RequiredArgsConstructor
public class PlaybackApiController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 녹화 찾기·구간 조회 로직은 각 서비스가 맡고, 컨트롤러는 파라미터 해석(시각 변환)과 응답만 한다.
    private final PlaybackFindService playbackFindService;
    private final PlaybackRangesService playbackRangesService;

    /**
     * 녹화 찾기(FNC-A-043). at(특정 시각)이나 latest=true(가장 최근) 중 하나가 필요하다.
     *
     * @param zoneId 구역 ID
     * @param at 찾을 시각(ISO-8601 문자열)
     * @param latest true면 at을 무시하고 가장 최근 녹화를 찾는다
     * @param request 응답 주소 앞에 붙일 컨텍스트 경로(/admin)를 얻기 위해 받는다
     */
    @GetMapping("/api/v1/playback")
    public PlaybackResponse findPlayback(
            @RequestParam("zoneId") Long zoneId,
            @RequestParam(name = "at", required = false) String at,
            @RequestParam(name = "latest", required = false, defaultValue = "false") boolean latest,
            HttpServletRequest request) {
        // 보관 기간(30일) 판정의 기준이 되는 지금 시각.
        LocalDateTime now = LocalDateTime.now(KST);
        // 응답의 영상 주소(videoUrl 등)가 /admin 아래 경로를 가리키도록 컨텍스트 경로를 넘긴다.
        String basePath = request.getContextPath();
        if (latest) {
            return playbackFindService.findLatest(zoneId, now, basePath);
        }
        // latest도 아니고 at도 없으면 무엇을 찾을지 알 수 없으므로 잘못된 요청이다.
        if (at == null || at.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "at 또는 latest=true가 필요합니다.");
        }
        return playbackFindService.findAt(zoneId, parseAt(at), now, basePath);
    }

    /** 이어 재생(FNC-A-045): 영상이 끝나면 같은 캠의 다음 녹화를 돌려준다(seekSec=0). */
    @GetMapping("/api/v1/recordings/{id}/next")
    public PlaybackResponse findNext(@PathVariable("id") Long id, HttpServletRequest request) {
        return playbackFindService.findNext(id, request.getContextPath());
    }

    /**
     * 시간 범위 안에서 녹화가 있는 구간 목록(다시보기 시간축의 띠)을 돌려준다(FNC-A-043 구간 조회).
     *
     * @param from 범위 시작 시각
     * @param to 범위 끝 시각
     */
    @GetMapping("/api/v1/playback/ranges")
    public PlaybackRangesResponse findRanges(
            @RequestParam("zoneId") Long zoneId, @RequestParam("from") String from, @RequestParam("to") String to) {
        return playbackRangesService.findRanges(
                zoneId, parseTime(from, "from"), parseTime(to, "to"), LocalDateTime.now(KST));
    }

    // 오프셋이 있으면 한국 시간으로 바꾸고, 없으면 한국 시간으로 본다. 주소에서 '+'가 공백이 된 경우도 받는다
    /** at 파라미터를 한국 시간 LocalDateTime으로 바꾼다(실제 변환은 parseTime). */
    private LocalDateTime parseAt(String at) {
        return parseTime(at, "at");
    }

    /**
     * 시각 문자열을 DB와 같은 기준(KST, 시간대 없는 LocalDateTime)으로 바꾼다.
     * 오프셋이 있으면 한국 시간으로 환산하고, 오프셋이 없으면 이미 한국 시간으로 본다.
     *
     * @param name 오류 메시지에 쓸 파라미터 이름(at, from, to)
     */
    private LocalDateTime parseTime(String at, String name) {
        // 주소에 +09:00을 그대로 쓰면 '+'가 공백으로 해석되어 들어오므로 되돌려 준다.
        String text = at.trim().replace(' ', '+');
        try {
            // 오프셋이 있는 형식(2026-10-12T14:03:00+09:00)을 먼저 시도한다.
            return OffsetDateTime.parse(text).atZoneSameInstant(KST).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                // 오프셋이 없는 형식이면 한국 시간으로 간주한다.
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT, name + " 시각은 2026-10-12T14:03:00+09:00 형식이어야 합니다.", e);
            }
        }
    }
}
