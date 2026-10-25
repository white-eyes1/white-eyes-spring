package com.whiteeyes.admin.event.scheduler;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.event.service.CamFaultEventService;

/** 캠 이상 감지를 10초마다 돌린다(FNC-A-035). 판정은 {@link CamFaultEventService}가 맡는다. */
@Component
public class CamFaultScheduler {

    private static final Logger log = LoggerFactory.getLogger(CamFaultScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CamFaultEventService camFaultEventService;

    public CamFaultScheduler(CamFaultEventService camFaultEventService) {
        this.camFaultEventService = camFaultEventService;
    }

    @Scheduled(fixedDelay = 10000)
    public void detect() {
        try {
            camFaultEventService.detect(LocalDateTime.now(KST));
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 주기에 다시 검사하도록 예외를 여기서 끊는다
            log.error("캠 이상 감지 실패", e);
        }
    }
}
