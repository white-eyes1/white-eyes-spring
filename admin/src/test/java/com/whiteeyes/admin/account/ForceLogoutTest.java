package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
/** 강제 로그아웃(FNC-A-008): 세션 삭제, 권한, 자기 자신 불가, 작업 기록, 대상 브라우저의 다음 요청 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class ForceLogoutTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String USER_LOGOUT_URL = "/admin/api/v1/users/{id}/logout";
    private static final String ADMIN_LOGOUT_URL = "/admin/api/v1/admins/{id}/logout";
    private static final String PASSWORD = "test-password-1!";
    private static final long FAR_FUTURE_MILLIS = 4_102_444_800_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long superId;
    private long adminId;

    @BeforeEach
    void setUp() {
        cleanTables();
        superId = insertAdmin("super01", "SUPER_ADMIN");
        adminId = insertAdmin("admin01", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    // ---- 사용자 대상: POST /api/v1/users/{id}/logout ----

    @Test
    @DisplayName("FNC-A-008 사용자의 세션을 모두 지우고 지운 수를 돌려준다. 다른 사용자의 세션은 그대로다")
    void user_deletesAllSessionsOfThatUserOnly() throws Exception {
        long hongId = insertUser("hong01");
        insertUser("kim02");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "kim02");

        userLogout(hongId, login("admin01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSessions").value(2));

        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isZero();
        assertThat(sessionsOf("SPRING_SESSION_USER", "kim02")).isOne();
    }

    @Test
    @DisplayName("FNC-A-008 사용자 대상은 사용자 앱 세션 표만 지운다(같은 아이디의 관리자 세션은 그대로)")
    void user_leavesAdminSessionTableUntouched() throws Exception {
        long hongId = insertUser("hong01");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_ADMIN", "hong01");

        userLogout(hongId, login("admin01")).andExpect(status().isOk());

        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "hong01")).as("결정 0005: 표가 달라 섞이지 않음").isOne();
    }

    @Test
    @DisplayName("FNC-A-008 세션이 없으면 200이고 deletedSessions는 0이다")
    void user_withoutSession_ok() throws Exception {
        long hongId = insertUser("hong01");

        userLogout(hongId, login("admin01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSessions").value(0));
    }

    @Test
    @DisplayName("FNC-A-008 사용자 강제 로그아웃은 work_history에 ACCOUNT_CHANGE 1행(대상 이름은 아이디, deletedSessions)을 남긴다")
    void user_recordsAccountChange() throws Exception {
        long hongId = insertUser("hong01");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "hong01");

        userLogout(hongId, login("admin01")).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("admin01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("USER_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(hongId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("hong01");
        assertThat(detail(row).get("deletedSessions").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-008 없는 사용자는 404 NOT_FOUND이고 기록이 없다")
    void user_unknown_notFound() throws Exception {
        userLogout(999_999L, login("admin01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-008 로그인하지 않으면 401이고 세션도 기록도 그대로다")
    void user_anonymous_unauthorized() throws Exception {
        long hongId = insertUser("hong01");
        insertSession("SPRING_SESSION_USER", "hong01");

        mockMvc.perform(post(USER_LOGOUT_URL, hongId).contextPath(CONTEXT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 세션 삭제도 취소된다(같은 트랜잭션)")
    void user_historyFails_sessionsKept() throws Exception {
        long hongId = insertUser("hong01");
        insertSession("SPRING_SESSION_USER", "hong01");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(post("/api/v1/users/{id}/logout", hongId)
                        .with(csrf())
                        .with(AdminTestLogin.admin("a".repeat(21))))
                .andExpect(status().isInternalServerError());

        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    // ---- 관리자 대상: POST /api/v1/admins/{id}/logout ----

    @Test
    @DisplayName("FNC-A-008 검수 기준: 강제 로그아웃한 관리자의 브라우저는 다음 요청에서 로그인 화면으로 이동한다")
    void admin_targetBrowser_redirectedToLoginOnNextRequest() throws Exception {
        Cookie target = login("admin01");
        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(target)).andExpect(status().isOk());

        adminLogout(adminId, login("super01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSessions").value(1));

        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(target))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/admin/api/v1/meta/levels").contextPath(CONTEXT_PATH).cookie(target))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-008 관리자 대상은 관리자 앱 세션 표만 지운다(같은 아이디의 사용자 세션은 그대로)")
    void admin_leavesUserSessionTableUntouched() throws Exception {
        insertSession("SPRING_SESSION_USER", "admin01");

        adminLogout(adminId, login("super01")).andExpect(status().isOk());

        assertThat(sessionsOf("SPRING_SESSION_USER", "admin01")).as("결정 0005: 표가 달라 섞이지 않음").isOne();
    }

    @Test
    @DisplayName("FNC-A-008 세션이 없는 관리자는 200이고 deletedSessions는 0이다")
    void admin_withoutSession_ok() throws Exception {
        long idleId = insertAdmin("admin02", "ADMIN");

        adminLogout(idleId, login("super01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSessions").value(0));
    }

    @Test
    @DisplayName("FNC-A-008 자기 자신은 400 INVALID_INPUT이고 내 세션은 그대로다")
    void admin_self_badRequest() throws Exception {
        Cookie own = login("super01");

        adminLogout(superId, own)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(own)).andExpect(status().isOk());
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-008 일반 관리자는 관리자를 로그아웃시킬 수 없다(403)")
    void admin_byAdminRole_forbidden() throws Exception {
        long otherId = insertAdmin("admin02", "ADMIN");
        Cookie other = login("admin02");

        adminLogout(adminId, login("admin01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(other)).andExpect(status().isOk());
        assertThat(otherId).isPositive();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-008 로그인하지 않으면 401")
    void admin_anonymous_unauthorized() throws Exception {
        mockMvc.perform(post(ADMIN_LOGOUT_URL, adminId).contextPath(CONTEXT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-008 없는 관리자는 404 NOT_FOUND이고 기록이 없다")
    void admin_unknown_notFound() throws Exception {
        adminLogout(999_999L, login("super01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 관리자 대상도 기록 저장이 실패하면 세션이 그대로 남는다(삭제는 맨 뒤)")
    void admin_historyFails_sessionsKept() throws Exception {
        insertSession("SPRING_SESSION_ADMIN", "admin01");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(post("/api/v1/admins/{id}/logout", adminId)
                        .with(csrf())
                        .with(AdminTestLogin.superAdmin("s".repeat(21))))
                .andExpect(status().isInternalServerError());

        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "admin01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-008 관리자 강제 로그아웃은 work_history에 ACCOUNT_CHANGE 1행(ADMIN_ACCOUNT, deletedSessions)을 남긴다")
    void admin_recordsAccountChange() throws Exception {
        login("admin01");

        adminLogout(adminId, login("super01")).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(superId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("super01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("admin01");
        assertThat(detail(row).get("deletedSessions").asInt()).isEqualTo(1);
    }

    private ResultActions userLogout(long userId, Cookie session) throws Exception {
        return mockMvc.perform(
                post(USER_LOGOUT_URL, userId).contextPath(CONTEXT_PATH).cookie(session).with(csrf()));
    }

    private ResultActions adminLogout(long targetId, Cookie session) throws Exception {
        return mockMvc.perform(
                post(ADMIN_LOGOUT_URL, targetId).contextPath(CONTEXT_PATH).cookie(session).with(csrf()));
    }

    private Cookie login(String loginId) throws Exception {
        Cookie session = mockMvc.perform(post("/admin/login")
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

    private long insertUser(String loginId) {
        jdbcTemplate.update(
                "INSERT INTO user_account (login_id, password, name, status) VALUES (?, 'x', '홍길동', 'ACTIVE')",
                loginId);
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE login_id = ?", Long.class, loginId);
    }

    // 사용자 앱이 만든 세션을 흉내 낸다. 시각은 epoch ms
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

    // H2는 JSON 칼럼을 바이트로 돌려준다
    private JsonNode detail(Map<String, Object> row) {
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        return detail;
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
