package com.whiteeyes.common.stat.controller;

import com.whiteeyes.common.stat.service.StatStreamBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/stats/stream
// 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
/** 실시간 통계 갱신(FNC-C-022): 새 1분 통계를 SSE(event: stat)로 받는다. */
@RestController
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 SSE도 5초 확인도 필요 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class StatStreamController {

    private final StatStreamBroadcaster statStreamBroadcaster;

    @GetMapping(path = "/api/v1/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(name = "regionId", required = false) Long regionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                // nginx가 응답을 모아 두지 않고 바로 내보내게 한다(conventions.md 'nginx 경로 규칙')
                .header("X-Accel-Buffering", "no")
                .body(statStreamBroadcaster.subscribe(regionId));
    }
}
