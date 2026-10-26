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
/** 관리자 비밀번호 재설정(FNC-A-010): 임시 비밀번호, 변경 강제, 세션 삭제, 작업 기록, 최고 관리자 대상 거부, 권한 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminPasswordResetTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String RESET_URL = "/admin/api/v1/admins/{id}/reset-password";
    private static final String PASSWORD = "test-password-1!";
    private static final String OLD_TARGET_PASSWORD = "old-admin-pass-1!";
    private static final long FAR_FUTURE_MILLIS = 4_102_444_800_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long superId;

    @BeforeEach
    void setUp() {
        cleanTables();
        superId = insertAdmin("super01", "SUPER_ADMIN", "ACTIVE", PASSWORD);
        insertAdmin("admin01", "ADMIN", "ACTIVE", PASSWORD);
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    @Test
    @DisplayName("FNC-A-010 임시 비밀번호는 응답으로 12자(대문자·소문자·숫자 포함)를 한 번 돌려준다")
    void returnsTemporaryPassword() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        String temporaryPassword = reset(targetId, login("super01"));

        assertThat(temporaryPassword)
                .hasSize(12)
                .matches(".*[A-Z].*")
                .matches(".*[a-z].*")
                .matches(".*[0-9].*")
                .matches("[A-Za-z0-9]{12}");
    }

    @Test
    @DisplayName("FNC-A-010 bcrypt 값만 저장하고 must_change_password=1로 바꾼다(다음 로그인 때 변경 강제)")
    void storesBcryptAndForcesChange() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        String temporaryPassword = reset(targetId, login("super01"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        String stored = (String) row.get("PASSWORD");
        assertThat(stored).startsWith("$2").isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, stored)).isTrue();
        assertThat(passwordEncoder.matches(OLD_TARGET_PASSWORD, stored)).as("이전 비밀번호는 더 쓸 수 없다").isFalse();
        assertThat(((Number) row.get("MUST_CHANGE_PASSWORD")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-010 검수 기준: 임시 비밀번호로 로그인하면 비밀번호 변경 화면으로 보낸다")
    void loginWithTemporaryPassword_redirectsToChange() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        String temporaryPassword = reset(targetId, login("super01"));

        Cookie session = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", "kim01")
                        .param("password", temporaryPassword)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
        assertThat(session).isNotNull();
        mockMvc.perform(get("/admin/stats")
                        .contextPath(CONTEXT_PATH)
                        .cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/me/password"));
    }

    @Test
    @DisplayName("FNC-A-010 이름·상태·역할 등 다른 칸은 그대로다")
    void leavesOtherColumns() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        reset(targetId, login("super01"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        assertThat(row.get("NAME")).isEqualTo("테스트 관리자");
        assertThat(row.get("ROLE")).isEqualTo("ADMIN");
        assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("FNC-A-010 그 관리자의 세션을 모두 지운다. 다른 관리자와 같은 아이디의 사용자 세션은 그대로다")
    void deletesAllSessionsOfThatAdminOnly() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);
        insertSession("SPRING_SESSION_ADMIN", "kim01");
        insertSession("SPRING_SESSION_ADMIN", "kim01");
        insertSession("SPRING_SESSION_ADMIN", "admin01");
        insertSession("SPRING_SESSION_USER", "kim01");
        Cookie session = login("super01");

        reset(targetId, session);

        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "kim01")).isZero();
        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "admin01")).isOne();
        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "super01")).as("호출한 최고 관리자 세션").isOne();
        assertThat(sessionsOf("SPRING_SESSION_USER", "kim01")).as("결정 0005: 표가 달라 섞이지 않음").isOne();
    }

    @Test
    @DisplayName("FNC-A-010 세션이 없는 관리자도 200이다")
    void withoutSession_ok() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        resetRequest(targetId, login("super01")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-010 명세에 상태 제한이 없어 비활성화(DISABLED)된 관리자도 재설정된다")
    void disabledAdmin_ok() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "DISABLED", OLD_TARGET_PASSWORD);

        resetRequest(targetId, login("super01")).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT must_change_password FROM admin_account WHERE id = ?", Integer.class, targetId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-010 작업 기록: ACCOUNT_CHANGE·ADMIN_ACCOUNT, 대상 이름은 아이디, detail은 {\"password\":\"변경됨\"}이고 비밀번호 값은 없다")
    void recordsAccountChange() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        String temporaryPassword = reset(targetId, login("super01"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(superId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("super01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(targetId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("kim01");
        String detailText = new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8);
        JsonNode detail = jsonMapper.readTree(detailText);
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        assertThat(detail.size()).isOne();
        assertThat(detail.get("password").asString()).isEqualTo("변경됨");
        assertThat(detailText).doesNotContain(temporaryPassword);
        assertThat(row.toString()).as("작업 기록 어느 칸에도 임시 비밀번호가 없다").doesNotContain(temporaryPassword);
    }

    @Test
    @DisplayName("FNC-A-010 없는 관리자는 404 NOT_FOUND이고 기록이 없다")
    void unknownAdmin_notFound() throws Exception {
        resetRequest(999_999L, login("super01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 대상은 role=ADMIN만이다. 최고 관리자는 400 INVALID_INPUT이고 비밀번호·기록이 그대로다")
    void superAdminTarget_badRequest() throws Exception {
        resetRequest(superId, login("super01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(passwordEncoder.matches(PASSWORD, storedPasswordOf(superId))).isTrue();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 일반 관리자가 부르면 403이고 비밀번호·세션·기록이 그대로다")
    void admin_forbidden() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);
        insertSession("SPRING_SESSION_ADMIN", "kim01");

        resetRequest(targetId, login("admin01")).andExpect(status().isForbidden());

        assertThat(passwordEncoder.matches(OLD_TARGET_PASSWORD, storedPasswordOf(targetId)))
                .isTrue();
        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "kim01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 로그인하지 않으면 401이다")
    void anonymous_unauthorized() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);

        mockMvc.perform(post(RESET_URL, targetId).contextPath(CONTEXT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(passwordEncoder.matches(OLD_TARGET_PASSWORD, storedPasswordOf(targetId)))
                .isTrue();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 비밀번호 변경과 세션 삭제도 취소된다(같은 트랜잭션)")
    void historyFails_everythingRolledBack() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE", OLD_TARGET_PASSWORD);
        insertSession("SPRING_SESSION_ADMIN", "kim01");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(post("/api/v1/admins/{id}/reset-password", targetId)
                        .with(csrf())
                        .with(AdminTestLogin.superAdmin("a".repeat(21))))
                .andExpect(status().isInternalServerError());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        assertThat(passwordEncoder.matches(OLD_TARGET_PASSWORD, (String) row.get("PASSWORD")))
                .as("비밀번호 변경이 취소됐다")
                .isTrue();
        assertThat(((Number) row.get("MUST_CHANGE_PASSWORD")).intValue()).isZero();
        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "kim01")).as("세션 삭제는 기록 뒤에 하므로 남아 있다").isOne();
        assertThat(count("work_history")).isZero();
    }

    // 응답 본문에서 임시 비밀번호를 꺼낸다
    private String reset(long adminId, Cookie session) throws Exception {
        String body = resetRequest(adminId, session)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return jsonMapper.readTree(body).get("temporaryPassword").asString();
    }

    private ResultActions resetRequest(long adminId, Cookie session) throws Exception {
        return mockMvc.perform(post(RESET_URL, adminId)
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

    private long insertAdmin(String loginId, String role, String adminStatus, String rawPassword) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '테스트 관리자', ?, ?, 0)",
                loginId,
                passwordEncoder.encode(rawPassword),
                role,
                adminStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM admin_account WHERE login_id = ?", Long.class, loginId);
    }

    private String storedPasswordOf(long adminId) {
        return jdbcTemplate.queryForObject("SELECT password FROM admin_account WHERE id = ?", String.class, adminId);
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
    }
}
