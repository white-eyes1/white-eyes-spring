package com.whiteeyes.admin.account.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.account.config.ActiveWindowProperties;
import com.whiteeyes.admin.account.repository.SessionActivityRepository;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;

/** 사용자·관리자 계정의 최근 활동 시각과 활동 중 여부를 계산한다(FNC-A-002·003 처리 순서 2). */
@Service
@EnableConfigurationProperties(ActiveWindowProperties.class)
@RequiredArgsConstructor
public class AccountActivityService {

    // 세션 표에서 마지막 접근 시각을 읽는 저장소
    private final SessionActivityRepository sessionActivityRepository;
    // 활동 중으로 보는 기준 시간 설정(기본 30분)
    private final ActiveWindowProperties activeWindowProperties;
    // 현재 시각과 시간대를 주는 시계. 시스템 시각을 직접 부르지 않고 주입받는다
    private final Clock clock;

    /**
     * 주어진 아이디들의 세션을 한 번의 쿼리로 읽는다. 목록 한 번에 한 번만 부른다(FNC-A-002 업무 규칙).
     *
     * @param table 사용자 목록은 USER, 관리자 목록은 ADMIN
     */
    public AccountActivities loadActivities(SessionTable table, Collection<String> loginIds) {
        // 지금에서 기준 시간을 뺀 시각. 이 이후에 접근한 계정이 활동 중이다
        LocalDateTime activeSince = LocalDateTime.now(clock).minus(activeWindowProperties.activeWindow());
        // 목록 전체 아이디의 세션을 한 번에 읽어 계산 도구에 담아 돌려준다
        return new AccountActivities(
                sessionActivityRepository.findLastAccessTimes(table, loginIds), clock.getZone(), activeSince);
    }
}
