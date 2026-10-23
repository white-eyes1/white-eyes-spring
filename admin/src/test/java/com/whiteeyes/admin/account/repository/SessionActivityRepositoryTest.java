package com.whiteeyes.admin.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

/** 세션의 마지막 접근 시각 조회(FNC-A-002·003). */
@SpringBootTest
@Sql("/sql/account/user-sessions.sql")
@Sql(statements = "DELETE FROM SPRING_SESSION_USER", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class SessionActivityRepositoryTest {

    @Autowired
    private SessionActivityRepository sessionActivityRepository;

    @MockitoSpyBean
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSpy() {
        clearInvocations(jdbcTemplate);
    }

    @Test
    @DisplayName("FNC-A-002 아이디별 세션 마지막 접근 시각의 최댓값을 돌려주고, 만료됐지만 남은 세션도 넣는다")
    void findLastAccessTimes_maxPerLoginId_includesExpiredSessions() {
        Map<String, Instant> result = sessionActivityRepository.findLastAccessTimes(SessionTable.USER, List.of("hong01", "kim02"));

        assertThat(result)
                .containsOnly(
                        Map.entry("hong01", Instant.ofEpochMilli(1792000200000L)),
                        Map.entry("kim02", Instant.ofEpochMilli(1792000300000L)));
    }

    @Test
    @DisplayName("FNC-A-002 세션이 없는 아이디는 결과에 없다")
    void findLastAccessTimes_noSession_absent() {
        Map<String, Instant> result = sessionActivityRepository.findLastAccessTimes(SessionTable.USER, List.of("lee03", "kim02"));

        assertThat(result).containsOnlyKeys("kim02");
    }

    @Test
    @DisplayName("FNC-A-002 아이디가 여러 개여도 쿼리는 한 번")
    void findLastAccessTimes_singleQuery() {
        sessionActivityRepository.findLastAccessTimes(SessionTable.USER, List.of("hong01", "kim02", "lee03"));

        verify(jdbcTemplate, times(1)).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    @Test
    @DisplayName("FNC-A-002 아이디가 없으면 쿼리를 보내지 않는다")
    void findLastAccessTimes_empty_noQuery() {
        assertThat(sessionActivityRepository.findLastAccessTimes(SessionTable.USER, List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }
}
