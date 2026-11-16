package com.whiteeyes.common.stat.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.whiteeyes.common.stat.service.StatStreamBroadcaster;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/stats/stream
// 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
/** 실시간 통계 갱신(FNC-C-022): 새 1분 통계를 SSE(event: stat)로 받는다. */
@RestController
@RequiredArgsConstructor
public class StatStreamController {

    // 구독자 목록 관리와 이벤트 전송은 브로드캐스터가 맡는다
    private final StatStreamBroadcaster statStreamBroadcaster;

    /**
     * SSE(서버가 계속 열어 두고 이벤트를 밀어 주는 방식) 연결을 연다.
     *
     * @param regionId 구독할 지역 번호. 비우면 전체
     */
    @GetMapping(path = "/api/v1/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(name = "regionId", required = false) Long regionId) {
        return ResponseEntity.ok()
                // 스트림 응답이 중간에서 캐시되지 않게 한다
                .cacheControl(CacheControl.noCache())
                // nginx가 응답을 모아 두지 않고 바로 내보내게 한다(conventions.md 'nginx 경로 규칙')
                .header("X-Accel-Buffering", "no")
                // 구독을 등록하고 만든 SseEmitter를 응답 본문으로 돌려주면, 스프링이 연결을 열어 둔 채 이후 이벤트를 흘려 보낸다
                .body(statStreamBroadcaster.subscribe(regionId));
    }
}
