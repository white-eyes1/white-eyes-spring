package com.whiteeyes.admin.account.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 세션 표에서 계정별 마지막 접근 시각을 읽는다(FNC-A-002·003, conventions.md '활동 중 판단').
 * 사용자는 사용자 앱 세션 표(SPRING_SESSION_USER), 관리자는 관리자 앱 세션 표(SPRING_SESSION_ADMIN)를 본다.
 * Spring Session 표준 표라 엔티티 없이 JDBC로 읽는다. PRINCIPAL_NAME은 로그인 아이디다.
 */
@Repository
@RequiredArgsConstructor
public class SessionActivityRepository {

    /** 읽을 세션 표. SQL에 넣는 표 이름은 이 두 값에서만 나온다(요청 값으로 받지 않음). */
    public enum SessionTable {
        // 사용자 앱의 세션 표
        USER("SPRING_SESSION_USER"),
        // 관리자 앱의 세션 표
        ADMIN("SPRING_SESSION_ADMIN");

        // 이 표에서 계정별 마지막 접근 시각을 구하는 SQL. :loginIds 자리는 실행할 때 채운다
        private final String lastAccessSql;

        // 만료됐지만 정리 작업이 아직 지우지 않은 세션도 마지막 접근 시각에 넣는다(#32에서 정함). 그래서 EXPIRY_TIME 조건이 없다
        SessionTable(String tableName) {
            // 같은 계정에 세션이 여럿(여러 기기·브라우저)일 수 있어 MAX로 가장 최근 접근 시각 하나만 고른다.
            // 표 이름은 값 바인딩을 못 하는 자리라 formatted로 끼우지만, 이 enum의 고정 값에서만 오므로 안전하다
            this.lastAccessSql =
                    """
                    SELECT PRINCIPAL_NAME, MAX(LAST_ACCESS_TIME) AS LAST_ACCESS_TIME
                    FROM %s
                    WHERE PRINCIPAL_NAME IN (:loginIds)
                    GROUP BY PRINCIPAL_NAME
                    """
                            .formatted(tableName);
        }
    }

    // :이름 형태의 파라미터를 쓰는 JDBC 도구. IN (:loginIds)에 목록을 그대로 넘길 수 있다
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 아이디마다 따로 조회하지 않고 한 번의 쿼리로 모은다(FNC-A-002 업무 규칙). 세션이 없는 아이디는 결과에 없다.
     */
    public Map<String, Instant> findLastAccessTimes(SessionTable table, Collection<String> loginIds) {
        // IN () 은 문법 오류가 될 수 있어, 빈 목록이면 DB를 부르지 않고 바로 빈 결과를 돌려준다
        if (loginIds.isEmpty()) {
            return Map.of();
        }
        // 로그인 아이디 -> 마지막 접근 시각
        Map<String, Instant> lastAccessTimes = new HashMap<>();
        // 결과 한 줄마다 불리는 처리기. LAST_ACCESS_TIME은 epoch 밀리초 값이라 Instant로 바꿔 맵에 담는다
        RowCallbackHandler collect = resultSet -> lastAccessTimes.put(
                resultSet.getString("PRINCIPAL_NAME"), Instant.ofEpochMilli(resultSet.getLong("LAST_ACCESS_TIME")));
        // 목록 전체 아이디를 한 번의 쿼리로 조회한다(아이디마다 따로 조회하지 않음)
        jdbcTemplate.query(table.lastAccessSql, Map.of("loginIds", loginIds), collect);
        return lastAccessTimes;
    }
}
