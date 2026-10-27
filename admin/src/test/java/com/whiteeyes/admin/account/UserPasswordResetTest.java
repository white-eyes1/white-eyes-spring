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
/** 사용자 비밀번호 재설정(FNC-A-005): 임시 비밀번호, 변경 강제, 세션 삭제, 작업 기록, 권한 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class UserPasswordResetTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String RESET_URL = "/admin/api/v1/users/{id}/reset-password";
    private static final String PASSWORD = "test-password-1!";
    private static final String OLD_USER_PASSWORD = "old-user-pass-1!";
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
    @DisplayName("FNC-A-005 임시 비밀번호는 응답으로 12자(대문자·소문자·숫자 포함)를 한 번 돌려준다")
    void returnsTemporaryPassword() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        String temporaryPassword = reset(hongId, login("admin01"));

        assertThat(temporaryPassword)
                .hasSize(12)
                .matches(".*[A-Z].*")
                .matches(".*[a-z].*")
                .matches(".*[0-9].*")
                .matches("[A-Za-z0-9]{12}");
    }

    @Test
    @DisplayName("FNC-A-005 검수 기준: bcrypt 값만 저장하고 must_change_password=1로 바꾼다(다음 로그인 때 변경 강제)")
    void storesBcryptAndForcesChange() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        String temporaryPassword = reset(hongId, login("admin01"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        String stored = (String) row.get("PASSWORD");
        assertThat(stored).startsWith("$2").isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, stored)).isTrue();
        assertThat(passwordEncoder.matches(OLD_USER_PASSWORD, stored)).as("이전 비밀번호는 더 쓸 수 없다").isFalse();
        assertThat(((Number) row.get("MUST_CHANGE_PASSWORD")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-005 이름·상태 등 다른 칸은 그대로다")
    void leavesOtherColumns() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        reset(hongId, login("admin01"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        assertThat(row.get("NAME")).isEqualTo("홍길동");
        assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("FNC-A-005 그 사용자의 세션을 모두 지운다. 다른 사용자와 같은 아이디의 관리자 세션은 그대로다")
    void deletesAllSessionsOfThatUserOnly() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        insertUser("kim02", "ACTIVE");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "hong01");
        insertSession("SPRING_SESSION_USER", "kim02");
        insertSession("SPRING_SESSION_ADMIN", "hong01");

        reset(hongId, login("admin01"));

        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isZero();
        assertThat(sessionsOf("SPRING_SESSION_USER", "kim02")).isOne();
        assertThat(sessionsOf("SPRING_SESSION_ADMIN", "hong01")).as("결정 0005: 표가 달라 섞이지 않음").isOne();
    }

    @Test
    @DisplayName("FNC-A-005 세션이 없는 사용자도 200이다")
    void withoutSession_ok() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        resetRequest(hongId, login("admin01")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "ACTIVE"})
    @DisplayName("FNC-A-005 미승인·정상 계정은 재설정된다")
    void pendingAndActive_ok(String userStatus) throws Exception {
        long id = insertUser("hong01", userStatus);

        resetRequest(id, login("admin01")).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT must_change_password FROM user_account WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-005 탈퇴 계정은 409 INVALID_STATUS이고 비밀번호·기록이 그대로다")
    void withdrawn_conflict() throws Exception {
        long id = insertUser("hong01", "WITHDRAWN");
        String before = jdbcTemplate.queryForObject("SELECT password FROM user_account WHERE id = ?", String.class, id);

        resetRequest(id, login("admin01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(jdbcTemplate.queryForObject("SELECT password FROM user_account WHERE id = ?", String.class, id))
                .isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT must_change_password FROM user_account WHERE id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_history", Long.class)).isZero();
    }

    @Test
    @DisplayName("FNC-A-005 작업 기록: ACCOUNT_CHANGE·USER_ACCOUNT, 대상 이름은 아이디, detail은 {\"password\":\"변경됨\"}이고 비밀번호 값은 없다")
    void recordsAccountChange() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        String temporaryPassword = reset(hongId, login("admin01"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("admin01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("USER_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(hongId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("hong01");
        String detailText = new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8);
        JsonNode detail = jsonMapper.readTree(detailText);
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        assertThat(detail.size()).isOne();
        assertThat(detail.get("password").asString()).isEqualTo("변경됨");
        assertThat(detailText).doesNotContain(temporaryPassword);
        assertThat(row.toString()).as("작업 기록 어느 칸에도 임시 비밀번호가 없다").doesNotContain(temporaryPassword);
    }

    @Test
    @DisplayName("FNC-A-005 관리자와 최고 관리자 모두 부를 수 있다")
    void adminAndSuperAdmin_allowed() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        resetRequest(hongId, login("admin01")).andExpect(status().isOk());
        resetRequest(hongId, login("super01")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-005 없는 사용자는 404 NOT_FOUND이고 기록이 없다")
    void unknownUser_notFound() throws Exception {
        resetRequest(999_999L, login("admin01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-005 로그인하지 않으면 401이고 비밀번호·세션·기록이 그대로다")
    void anonymous_unauthorized() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        insertSession("SPRING_SESSION_USER", "hong01");

        mockMvc.perform(post(RESET_URL, hongId)
                        .contextPath(CONTEXT_PATH)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(passwordEncoder.matches(OLD_USER_PASSWORD, storedPasswordOf(hongId))).isTrue();
        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 비밀번호 변경과 세션 삭제도 취소된다(같은 트랜잭션)")
    void historyFails_everythingRolledBack() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        insertSession("SPRING_SESSION_USER", "hong01");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(post(
                                "/api/v1/users/{id}/reset-password", hongId)
                        .with(csrf())
                        .with(AdminTestLogin.admin("a".repeat(21))))
                .andExpect(status().isInternalServerError());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        assertThat(passwordEncoder.matches(OLD_USER_PASSWORD, (String) row.get("PASSWORD")))
                .as("비밀번호 변경이 취소됐다")
                .isTrue();
        assertThat(((Number) row.get("MUST_CHANGE_PASSWORD")).intValue()).isZero();
        assertThat(sessionsOf("SPRING_SESSION_USER", "hong01")).isOne();
        assertThat(count("work_history")).isZero();
    }

    // 응답 본문에서 임시 비밀번호를 꺼낸다
    private String reset(long userId, Cookie session) throws Exception {
        String body = resetRequest(userId, session)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return jsonMapper.readTree(body).get("temporaryPassword").asString();
    }

    private ResultActions resetRequest(long userId, Cookie session) throws Exception {
        return mockMvc.perform(post(
                        RESET_URL, userId)
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

    // 이전 비밀번호(OLD_USER_PASSWORD)를 bcrypt로 넣어 두고, 재설정 뒤 더는 맞지 않는지 확인한다
    private long insertUser(String loginId, String userStatus) {
        jdbcTemplate.update(
                "INSERT INTO user_account (login_id, password, name, status) VALUES (?, ?, '홍길동', ?)",
                loginId,
                passwordEncoder.encode(OLD_USER_PASSWORD),
                userStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE login_id = ?", Long.class, loginId);
    }

    private String storedPasswordOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT password FROM user_account WHERE id = ?", String.class, userId);
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
