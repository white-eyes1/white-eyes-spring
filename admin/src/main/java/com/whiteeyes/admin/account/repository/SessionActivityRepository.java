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
        USER("SPRING_SESSION_USER"),
        ADMIN("SPRING_SESSION_ADMIN");

        private final String lastAccessSql;

        // 만료됐지만 정리 작업이 아직 지우지 않은 세션도 마지막 접근 시각에 넣는다(#32에서 정함). 그래서 EXPIRY_TIME 조건이 없다
        SessionTable(String tableName) {
            this.lastAccessSql = """
                    SELECT PRINCIPAL_NAME, MAX(LAST_ACCESS_TIME) AS LAST_ACCESS_TIME
                    FROM %s
                    WHERE PRINCIPAL_NAME IN (:loginIds)
                    GROUP BY PRINCIPAL_NAME
                    """.formatted(tableName);
        }
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 아이디마다 따로 조회하지 않고 한 번의 쿼리로 모은다(FNC-A-002 업무 규칙). 세션이 없는 아이디는 결과에 없다.
     */
    public Map<String, Instant> findLastAccessTimes(SessionTable table, Collection<String> loginIds) {
        if (loginIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Instant> lastAccessTimes = new HashMap<>();
        RowCallbackHandler collect = resultSet -> lastAccessTimes.put(
                resultSet.getString("PRINCIPAL_NAME"), Instant.ofEpochMilli(resultSet.getLong("LAST_ACCESS_TIME")));
        jdbcTemplate.query(table.lastAccessSql, Map.of("loginIds", loginIds), collect);
        return lastAccessTimes;
    }
}
