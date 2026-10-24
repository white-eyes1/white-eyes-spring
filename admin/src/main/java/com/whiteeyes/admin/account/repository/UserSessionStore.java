package com.whiteeyes.admin.account.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 앱 세션 표(SPRING_SESSION_USER)를 읽고 지우는 세션 저장소(conventions.md '세션·로그인', FNC-A-008).
 * 관리자 앱의 세션 저장소는 관리자 앱 표(SPRING_SESSION_ADMIN) 하나뿐이라, 사용자 세션을 지우려고 표 이름만 다른 저장소를 하나 더 둔다.
 *
 * <p>빈이 아니라 이 클래스 안에서만 만든다. {@code SessionRepository} 빈을 하나 더 등록하면 Spring Boot의 세션 자동 구성이
 * 꺼지거나 기존 주입(로그인 세션, 실시간 화면 권한 확인, 비밀번호 변경)이 어느 저장소인지 헷갈린다.
 * 저장소의 트랜잭션은 호출한 쪽 트랜잭션에 합쳐지므로 상태 변경·작업 기록과 함께 취소된다.
 */
@Component
public class UserSessionStore {

    private static final String USER_SESSION_TABLE = "SPRING_SESSION_USER";

    private final JdbcIndexedSessionRepository sessionRepository;

    public UserSessionStore(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager) {
        this.sessionRepository =
                new JdbcIndexedSessionRepository(jdbcOperations, new TransactionTemplate(transactionManager));
        this.sessionRepository.setTableName(USER_SESSION_TABLE);
        this.sessionRepository.afterPropertiesSet();
    }

    /** 사용자 앱 세션을 로그인 아이디(PRINCIPAL_NAME)로 찾고 지우는 데 쓰는 저장소. */
    public FindByIndexNameSessionRepository<? extends Session> repository() {
        return sessionRepository;
    }
}
