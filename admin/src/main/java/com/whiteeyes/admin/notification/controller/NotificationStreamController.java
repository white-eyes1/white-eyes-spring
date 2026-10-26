package com.whiteeyes.admin.notification.controller;

import com.whiteeyes.admin.notification.service.NotificationBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 컨텍스트 경로가 붙어 /admin/api/v1/notifications/stream. 권한 '관리자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
/** 관리자 알림 스트림(FNC-A-048): 새 이벤트·미확인 수·캠 상태를 SSE로 받는다. */
@RestController
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 SSE가 필요 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class NotificationStreamController {

    private final NotificationBroadcaster notificationBroadcaster;

    @GetMapping(path = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                // nginx가 응답을 모아 두지 않고 바로 내보내게 한다(conventions.md 'nginx 경로 규칙')
                .header("X-Accel-Buffering", "no")
                .body(notificationBroadcaster.subscribe());
    }
}
