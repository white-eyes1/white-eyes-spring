package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 사용자 승인 검수 기준: 처리 순서·예외 처리(FNC-A-006). */
@SpringBootTest
@AutoConfigureMockMvc
class UserApproveTest {

    private static final String APPROVE_URL = "/api/v1/users/{id}/approve";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    @AfterEach
    void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM user_account");
    }

    @Test
    @DisplayName("FNC-A-006 PENDING 사용자를 승인하면 204이고 ACTIVE가 된다")
    void approve_pending_becomesActive() throws Exception {
        long userId = insertUser("hong", "PENDING");

        approve(userId, AdminTestLogin.admin()).andExpect(status().isNoContent());

        assertThat(statusOf(userId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("FNC-A-006 승인하면 work_history에 ACCOUNT_CHANGE 1행(status: PENDING→ACTIVE)이 생긴다")
    void approve_recordsAccountChange() throws Exception {
        long userId = insertUser("hong", "PENDING");

        approve(userId, AdminTestLogin.admin()).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("USER_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(userId);
        assertThat(row.get("TARGET_NAME")).as("계정의 대상 이름은 아이디").isEqualTo("hong");
        assertThat(detail(row).get("status").asString()).isEqualTo("PENDING→ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "WITHDRAWN"})
    @DisplayName("FNC-A-006 PENDING이 아니면 409 INVALID_STATUS이고 상태도 기록도 그대로다")
    void approve_notPending_conflict(String current) throws Exception {
        long userId = insertUser("hong", current);

        approve(userId, AdminTestLogin.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(statusOf(userId)).isEqualTo(current);
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-006 같은 사용자를 두 번 승인하면 두 번째는 409이고 기록은 1행이다")
    void approve_twice_secondConflict() throws Exception {
        long userId = insertUser("hong", "PENDING");

        approve(userId, AdminTestLogin.admin()).andExpect(status().isNoContent());
        approve(userId, AdminTestLogin.admin()).andExpect(status().isConflict());

        assertThat(count("work_history")).isOne();
    }

    @Test
    @DisplayName("FNC-A-006 없는 사용자를 승인하면 404 NOT_FOUND")
    void approve_unknownUser_notFound() throws Exception {
        approve(999_999L, AdminTestLogin.admin())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-006 로그인하지 않고 승인하면 401이고 상태도 기록도 그대로다")
    void approve_notLoggedIn_unauthorized() throws Exception {
        long userId = insertUser("hong", "PENDING");

        approve(userId, null).andExpect(status().isUnauthorized());

        assertThat(statusOf(userId)).isEqualTo("PENDING");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 승인도 취소된다(같은 트랜잭션)")
    void approve_historyFails_rolledBack() throws Exception {
        long userId = insertUser("hong", "PENDING");

        approve(userId, AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());

        assertThat(statusOf(userId)).isEqualTo("PENDING");
        assertThat(count("work_history")).isZero();
    }

    private ResultActions approve(long userId, RequestPostProcessor login) throws Exception {
        MockHttpServletRequestBuilder request = post(APPROVE_URL, userId).with(csrf());
        return mockMvc.perform(login == null ? request : request.with(login));
    }

    private long insertUser(String loginId, String status) {
        jdbcTemplate.update(
                "INSERT INTO user_account (login_id, password, name, status) VALUES (?, 'x', '홍길동', ?)",
                loginId,
                status);
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE login_id = ?", Long.class, loginId);
    }

    private String statusOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT status FROM user_account WHERE id = ?", String.class, userId);
    }

    // H2는 JSON 칼럼을 바이트로 돌려준다
    private JsonNode detail(Map<String, Object> row) {
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        return detail;
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
