package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.repository.UserSessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * 계정의 세션을 모두 지운다(FNC-A-008 처리 순서 2, FNC-A-011 처리 순서 1).
 * 사용자는 사용자 앱 세션 표(SPRING_SESSION_USER), 관리자는 관리자 앱 세션 표(SPRING_SESSION_ADMIN)에서 login_id로 찾는다.
 * 사용자와 관리자는 같은 아이디를 가질 수 있지만 표가 달라 섞이지 않는다(결정 0005).
 * 부르는 쪽 트랜잭션 안에서 불러야 상태 변경·작업 기록과 함께 취소된다.
 *
 * <p>세션 저장소는 웹 앱에만 있으므로 웹 서버 없이 띄우는 1회 작업(--job=…)에서는 등록하지 않는다.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccountSessionService {

    private final UserSessionStore userSessionStore;
    private final FindByIndexNameSessionRepository<? extends Session> adminSessionRepository;

    public AccountSessionService(
            UserSessionStore userSessionStore,
            FindByIndexNameSessionRepository<? extends Session> adminSessionRepository) {
        this.userSessionStore = userSessionStore;
        this.adminSessionRepository = adminSessionRepository;
    }

    /** 사용자의 세션을 모두 지우고 지운 수를 돌려준다. 세션이 없으면 0이다. */
    public int endUserSessions(String loginId) {
        return deleteAll(userSessionStore.repository(), loginId);
    }

    /** 관리자의 세션을 모두 지우고 지운 수를 돌려준다. 세션이 없으면 0이다. */
    public int endAdminSessions(String loginId) {
        return deleteAll(adminSessionRepository, loginId);
    }

    // 세션 저장소로만 찾고 지운다. SQL을 직접 쓰지 않는다(conventions.md '세션 주인 이름': Redis 전환 대비)
    private static int deleteAll(FindByIndexNameSessionRepository<? extends Session> repository, String loginId) {
        int deleted = 0;
        for (String sessionId : repository.findByPrincipalName(loginId).keySet()) {
            repository.deleteById(sessionId);
            deleted++;
        }
        return deleted;
    }
}
