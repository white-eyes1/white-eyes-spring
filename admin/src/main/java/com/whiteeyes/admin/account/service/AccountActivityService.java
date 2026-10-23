package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.config.ActiveWindowProperties;
import com.whiteeyes.admin.account.repository.SessionActivityRepository;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** 사용자·관리자 계정의 최근 활동 시각과 활동 중 여부를 계산한다(FNC-A-002·003 처리 순서 2). */
@Service
@EnableConfigurationProperties(ActiveWindowProperties.class)
@RequiredArgsConstructor
public class AccountActivityService {

    private final SessionActivityRepository sessionActivityRepository;
    private final ActiveWindowProperties activeWindowProperties;
    private final Clock clock;

    /**
     * 주어진 아이디들의 세션을 한 번의 쿼리로 읽는다. 목록 한 번에 한 번만 부른다(FNC-A-002 업무 규칙).
     *
     * @param table 사용자 목록은 USER, 관리자 목록은 ADMIN
     */
    public AccountActivities loadActivities(SessionTable table, Collection<String> loginIds) {
        LocalDateTime activeSince = LocalDateTime.now(clock).minus(activeWindowProperties.activeWindow());
        return new AccountActivities(
                sessionActivityRepository.findLastAccessTimes(table, loginIds), clock.getZone(), activeSince);
    }
}
