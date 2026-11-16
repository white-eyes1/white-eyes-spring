package com.whiteeyes.admin.event.scheduler;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.event.service.CamFaultEventService;

/** 캠 이상 감지를 10초마다 돌린다(FNC-A-035). 판정은 {@link CamFaultEventService}가 맡는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CamFaultScheduler {

    // 서비스 기준 시간대(한국). 서버 기본 시간대와 무관하게 같은 시각으로 판정하려고 고정한다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CamFaultEventService camFaultEventService;

    /**
     * 이전 실행이 끝난 뒤 10초를 쉬고 다시 실행한다(fixedDelay). 한 번의 감지가 10초보다 길어도 겹쳐 돌지 않는다.
     */
    @Scheduled(fixedDelay = 10000)
    public void detect() {
        try {
            // 현재 시각(KST)을 기준으로 캠 이상 여부를 판정한다
            camFaultEventService.detect(LocalDateTime.now(KST));
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 주기에 다시 검사하도록 예외를 여기서 끊는다
            log.error("캠 이상 감지 실패", e);
        }
    }
}
