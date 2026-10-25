package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.support.AdminTestLogin;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 권한은 계정마다 로그인 화면(POST /login)으로 실제 로그인한 세션 쿠키로 확인한다(#47과 같음)
/**
 * 사용자 승인 취소(FNC-A-007): ACTIVE → PENDING, 세션 삭제, 작업 기록, 409, 권한.
 * 검수 기준 중 사용자 앱 쪽(세션이 지워진 브라우저가 다음 요청에서 로그인 화면으로)은 user의 UserUnapproveEffectTest가 확인한다.
 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class UserUnapproveTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String UNAPPROVE_URL = "/admin/api/v1/users/{id}/unapprove";
    private static final String PASSWORD = "test-password-1!";
    private static final long FAR_FUTURE_MILLIS = 4_102_444_800_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long adminId;

    @BeforeEach
    void setUp() {
        cleanTables();
        adminId = insertAdmin("admin01", "ADMIN");
        insertAdmin("super01", "SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    @Test
    @DisplayName("FNC-A-007 ACTIVE 사용자를 PENDING으로 되돌리고 204를 돌려준다")
    void activeToPending() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        unapprove(hongId, login("admin01")).andExpect(status().isNoContent());

        assertThat(statusOf(hongId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("FNC-A-007 검수 기준(관리자 앱 쪽): 그 사용자의 세션을 모두 지운다. 다른 사용자와 같은 아이디의 관리자 세션은 그대로다")
    void deletesAllSessionsOfThatUserOnly() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        insertUser("kim02", "ACTIVE");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "kim02");
        insertSession("SPRING_SESSION_ADMIN", "hong01");

        unapprove(hongId, login("admin01")).andExpect(status().isNoContent());

        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isZero();
        assertThat(sessionsOf("SPRING_SESSION_USER", "kim02")).isOne();
        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "hong01")).as("결정 0005: 표가 달라 섞이지 않음").isOne();
    }

    @Test
    @DisplayName("FNC-A-007 작업 기록: ACCOUNT_CHANGE·USER_ACCOUNT, 대상 이름은 아이디, detail은 {\"status\":\"ACTIVE→PENDING\"}")
    void recordsAccountChange() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        unapprove(hongId, login("admin01")).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("USER_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(hongId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("hong01");
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.size()).isOne();
        assertThat(detail.get("status").asString()).isEqualTo("ACTIVE→PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "WITHDRAWN"})
    @DisplayName("FNC-A-007 ACTIVE가 아니면 409 INVALID_STATUS이고 상태·세션·기록이 그대로다")
    void notActive_conflict(String userStatus) throws Exception {
        long id = insertUser("hong01", userStatus);
        insertSession("SPRING_SESSION_USER", "hong01");

        unapprove(id, login("admin01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(statusOf(id)).isEqualTo(userStatus);
        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-007 없는 사용자는 404 NOT_FOUND이고 기록이 없다")
    void unknownUser_notFound() throws Exception {
        unapprove(999_999L, login("admin01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-007 관리자와 최고 관리자 모두 부를 수 있다")
    void adminAndSuperAdmin_allowed() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        long kimId = insertUser("kim02", "ACTIVE");

        unapprove(hongId, login("admin01")).andExpect(status().isNoContent());
        unapprove(kimId, login("super01")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("FNC-A-007 로그인하지 않으면 401이고 상태·세션·기록이 그대로다")
    void anonymous_unauthorized() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        insertSession("SPRING_SESSION_USER", "hong01");

        mockMvc.perform(post(UNAPPROVE_URL, hongId).contextPath(CONTEXT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(hongId)).isEqualTo("ACTIVE");
        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 상태 변경과 세션 삭제도 취소된다(같은 트랜잭션)")
    void historyFails_everythingRolledBack() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        insertSession("SPRING_SESSION_USER", "hong01");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(post("/api/v1/users/{id}/unapprove", hongId)
                        .with(csrf())
                        .with(AdminTestLogin.admin("a".repeat(21))))
                .andExpect(status().isInternalServerError());

        assertThat(statusOf(hongId)).isEqualTo("ACTIVE");
        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    private ResultActions unapprove(long userId, Cookie session) throws Exception {
        return mockMvc.perform(post(UNAPPROVE_URL, userId)
                .contextPath(CONTEXT_PATH)
                .cookie(session)
                .with(csrf()));
    }

    private Cookie login(String loginId) throws Exception {
        Cookie session = mockMvc.perform(
                        post("/admin/login")
                                .contextPath(CONTEXT_PATH)
                                .param("username", loginId)
                                .param("password", PASSWORD)
                                .with(csrf()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
        assertThat(session).as(loginId + " 로그인 세션").isNotNull();
        return session;
    }

    private long insertAdmin(String loginId, String role) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '테스트 관리자', ?, 'ACTIVE', 0)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                role);
        return jdbcTemplate.queryForObject("SELECT id FROM admin_account WHERE login_id = ?", Long.class, loginId);
    }

    private long insertUser(String loginId, String userStatus) {
        jdbcTemplate.update(
                "INSERT INTO user_account (login_id, password, name, status) VALUES (?, ?, '홍길동', ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                userStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE login_id = ?", Long.class, loginId);
    }

    private String statusOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT status FROM user_account WHERE id = ?", String.class, userId);
    }

    private void insertSession(String table, String principalName) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO " + table + " (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,"
                        + " MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME) VALUES (?, ?, ?, ?, 1800, ?, ?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                now,
                now,
                FAR_FUTURE_MILLIS,
                principalName);
    }

    private long sessionsOf(String table, String principalName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE PRINCIPAL_NAME = ?", Long.class, principalName);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_USER_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_USER");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
        jdbcTemplate.update("DELETE FROM user_account");
    }
}
