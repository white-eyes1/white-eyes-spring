package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.support.AdminTestLogin;
import jakarta.servlet.http.Cookie;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 권한은 계정마다 로그인 화면(POST /login)으로 실제 로그인한 세션 쿠키로 확인한다(#47과 같음)
/** 사용자 정보 수정(FNC-A-004): 저장, 작업 기록의 이전·이후 값, 입력값 규칙, 권한 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class UserUpdateTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String UPDATE_URL = "/admin/api/v1/users/{id}";
    private static final String PASSWORD = "test-password-1!";

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
    @DisplayName("FNC-A-004 이름·메일·연락처를 저장하고 수정된 사용자(목록 한 줄과 같은 모양)를 돌려준다")
    void savesAndReturnsUpdatedUser() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        update(hongId, Map.of("name", "홍길순", "email", "new@example.com", "phone", "010-9876-5432"), login("admin01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(hongId))
                .andExpect(jsonPath("$.loginId").value("hong01"))
                .andExpect(jsonPath("$.name").value("홍길순"))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.phone").value("010-9876-5432"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(false));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        assertThat(row.get("NAME")).isEqualTo("홍길순");
        assertThat(row.get("EMAIL")).isEqualTo("new@example.com");
        assertThat(row.get("PHONE")).isEqualTo("010-9876-5432");
    }

    @Test
    @DisplayName("FNC-A-004 검수 기준: 작업 기록(ACCOUNT_CHANGE·USER_ACCOUNT)에 바뀐 필드만 이전·이후 값으로 남는다")
    void recordsBeforeAndAfter() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        update(hongId, Map.of("name", "홍길동", "email", "new@example.com", "phone", ""), login("admin01"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("admin01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("USER_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(hongId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("hong01");
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        // 이름은 같아서 빠지고, 메일은 바뀌고, 연락처는 비워져 null이 된다
        assertThat(detail.get("before").propertyNames()).containsExactly("email", "phone");
        assertThat(detail.get("before").get("email").asString()).isEqualTo("hong@example.com");
        assertThat(detail.get("after").get("email").asString()).isEqualTo("new@example.com");
        assertThat(detail.get("before").get("phone").asString()).isEqualTo("010-1234-5678");
        assertThat(detail.get("after").get("phone").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM user_account WHERE id = ?", String.class, hongId))
                .as("빈 문자열은 NULL로 저장")
                .isNull();
    }

    @Test
    @DisplayName("FNC-A-004 바뀐 값이 없으면 200이고 작업 기록을 남기지 않는다")
    void noChange_noHistory() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        update(hongId, Map.of("name", "홍길동", "email", "hong@example.com", "phone", "010-1234-5678"), login("admin01"))
                .andExpect(status().isOk());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-004 보내지 않은 필드는 그대로 둔다")
    void omittedFieldsKept() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        update(hongId, Map.of("name", "홍길순"), login("admin01")).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        assertThat(row.get("NAME")).isEqualTo("홍길순");
        assertThat(row.get("EMAIL")).isEqualTo("hong@example.com");
        assertThat(row.get("PHONE")).isEqualTo("010-1234-5678");
    }

    @Test
    @DisplayName("FNC-A-004 업무 규칙: 아이디·상태는 요청에 있어도 바뀌지 않는다")
    void loginIdAndStatusNotChanged() throws Exception {
        long hongId = insertUser("hong01", "PENDING");

        update(hongId, Map.of("name", "홍길순", "loginId", "other01", "status", "ACTIVE"), login("admin01"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        assertThat(row.get("LOGIN_ID")).isEqualTo("hong01");
        assertThat(row.get("STATUS")).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "phone"})
    @DisplayName("FNC-A-004 입력값 규칙을 어기면 400 INVALID_INPUT(칸별 오류)이고 저장·기록이 없다")
    void invalidInput_badRequest(String field) throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");
        Map<String, String> invalid = Map.of("name", " ", "email", "not-mail", "phone", "abc");

        update(hongId, Map.of(field, invalid.get(field)), login("admin01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM user_account WHERE id = ?", hongId);
        assertThat(row.get("NAME")).isEqualTo("홍길동");
        assertThat(row.get("EMAIL")).isEqualTo("hong@example.com");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-004 탈퇴 계정은 409 INVALID_STATUS이고 값·기록이 그대로다")
    void withdrawn_conflict() throws Exception {
        long hongId = insertUser("hong01", "WITHDRAWN");

        update(hongId, Map.of("name", "홍길순"), login("admin01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM user_account WHERE id = ?", String.class, hongId))
                .isEqualTo("홍길동");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-004 없는 사용자는 404 NOT_FOUND이고 기록이 없다")
    void unknownUser_notFound() throws Exception {
        update(999_999L, Map.of("name", "홍길순"), login("admin01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-004 관리자와 최고 관리자 모두 부를 수 있다")
    void adminAndSuperAdmin_allowed() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        update(hongId, Map.of("name", "홍길순"), login("admin01")).andExpect(status().isOk());
        update(hongId, Map.of("name", "홍길자"), login("super01")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-004 로그인하지 않으면 401이고 값·기록이 그대로다")
    void anonymous_unauthorized() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        mockMvc.perform(patch(UPDATE_URL, hongId)
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"홍길순\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM user_account WHERE id = ?", String.class, hongId))
                .isEqualTo("홍길동");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 수정도 취소된다(같은 트랜잭션)")
    void historyFails_rolledBack() throws Exception {
        long hongId = insertUser("hong01", "ACTIVE");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(patch("/api/v1/users/{id}", hongId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"홍길순\"}")
                        .with(csrf())
                        .with(AdminTestLogin.admin("a".repeat(21))))
                .andExpect(status().isInternalServerError());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM user_account WHERE id = ?", String.class, hongId))
                .isEqualTo("홍길동");
        assertThat(count("work_history")).isZero();
    }

    private ResultActions update(long userId, Map<String, String> body, Cookie session) throws Exception {
        return mockMvc.perform(patch(UPDATE_URL, userId)
                .contextPath(CONTEXT_PATH)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body))
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
                "INSERT INTO user_account (login_id, password, name, email, phone, status)"
                        + " VALUES (?, ?, '홍길동', 'hong@example.com', '010-1234-5678', ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                userStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE login_id = ?", Long.class, loginId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
        jdbcTemplate.update("DELETE FROM user_account");
    }
}
