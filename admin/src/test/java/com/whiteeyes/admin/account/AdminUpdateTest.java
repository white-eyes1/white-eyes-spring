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
/** 관리자 정보 수정(FNC-A-010): 저장, 작업 기록의 이전·이후 값, 입력값 규칙, 최고 관리자 대상 거부, 권한 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminUpdateTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String UPDATE_URL = "/admin/api/v1/admins/{id}";
    private static final String PASSWORD = "test-password-1!";

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
        superId = insertAdmin("super01", "SUPER_ADMIN", "ACTIVE");
        insertAdmin("admin01", "ADMIN", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    @Test
    @DisplayName("FNC-A-010 이름·메일·연락처를 저장하고 수정된 관리자(목록 한 줄과 같은 모양)를 돌려준다")
    void savesAndReturnsUpdatedAdmin() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        update(targetId, Map.of("name", "김관리", "email", "new@example.com", "phone", "010-9876-5432"), login("super01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId))
                .andExpect(jsonPath("$.loginId").value("kim01"))
                .andExpect(jsonPath("$.name").value("김관리"))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.phone").value("010-9876-5432"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(false));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        assertThat(row.get("NAME")).isEqualTo("김관리");
        assertThat(row.get("EMAIL")).isEqualTo("new@example.com");
        assertThat(row.get("PHONE")).isEqualTo("010-9876-5432");
    }

    @Test
    @DisplayName("FNC-A-010 작업 기록(ACCOUNT_CHANGE·ADMIN_ACCOUNT)에 바뀐 필드만 이전·이후 값으로 남는다")
    void recordsBeforeAndAfter() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        update(targetId, Map.of("name", "테스트 관리자", "email", "new@example.com", "phone", ""), login("super01"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(superId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("super01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(targetId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("kim01");
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        // 이름은 같아서 빠지고, 메일은 바뀌고, 연락처는 비워져 null이 된다
        assertThat(detail.get("before").propertyNames()).containsExactly("email", "phone");
        assertThat(detail.get("before").get("email").asString()).isEqualTo("kim@example.com");
        assertThat(detail.get("after").get("email").asString()).isEqualTo("new@example.com");
        assertThat(detail.get("before").get("phone").asString()).isEqualTo("010-1234-5678");
        assertThat(detail.get("after").get("phone").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM admin_account WHERE id = ?", String.class, targetId))
                .as("빈 문자열은 NULL로 저장")
                .isNull();
    }

    @Test
    @DisplayName("FNC-A-010 바뀐 값이 없으면 200이고 작업 기록을 남기지 않는다")
    void noChange_noHistory() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        update(targetId, Map.of("name", "테스트 관리자", "email", "kim@example.com", "phone", "010-1234-5678"), login("super01"))
                .andExpect(status().isOk());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 보내지 않은 필드는 그대로 둔다")
    void omittedFieldsKept() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        update(targetId, Map.of("name", "김관리"), login("super01")).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        assertThat(row.get("NAME")).isEqualTo("김관리");
        assertThat(row.get("EMAIL")).isEqualTo("kim@example.com");
        assertThat(row.get("PHONE")).isEqualTo("010-1234-5678");
    }

    @Test
    @DisplayName("FNC-A-010 업무 규칙: 아이디·역할·상태는 요청에 있어도 바뀌지 않는다")
    void loginIdRoleStatusNotChanged() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        update(
                        targetId,
                        Map.of("name", "김관리", "loginId", "other01", "role", "SUPER_ADMIN", "status", "DISABLED"),
                        login("super01"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        assertThat(row.get("LOGIN_ID")).isEqualTo("kim01");
        assertThat(row.get("ROLE")).isEqualTo("ADMIN");
        assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("FNC-A-010 명세에 상태 제한이 없어 비활성화(DISABLED)된 관리자도 수정된다")
    void disabledAdmin_ok() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "DISABLED");

        update(targetId, Map.of("name", "김관리"), login("super01")).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM admin_account WHERE id = ?", String.class, targetId))
                .isEqualTo("김관리");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "phone"})
    @DisplayName("FNC-A-010 입력값 규칙을 어기면 400 INVALID_INPUT(칸별 오류)이고 저장·기록이 없다")
    void invalidInput_badRequest(String field) throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");
        Map<String, String> invalid = Map.of("name", " ", "email", "not-mail", "phone", "abc");

        update(targetId, Map.of(field, invalid.get(field)), login("super01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", targetId);
        assertThat(row.get("NAME")).isEqualTo("테스트 관리자");
        assertThat(row.get("EMAIL")).isEqualTo("kim@example.com");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 없는 관리자는 404 NOT_FOUND이고 기록이 없다")
    void unknownAdmin_notFound() throws Exception {
        update(999_999L, Map.of("name", "김관리"), login("super01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 대상은 role=ADMIN만이다. 최고 관리자는 400 INVALID_INPUT이고 값·기록이 그대로다(내 정보 화면에서 수정)")
    void superAdminTarget_badRequest() throws Exception {
        update(superId, Map.of("name", "바꿈"), login("super01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM admin_account WHERE id = ?", String.class, superId))
                .isEqualTo("테스트 관리자");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 일반 관리자가 부르면 403이고 값·기록이 그대로다")
    void admin_forbidden() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        update(targetId, Map.of("name", "김관리"), login("admin01")).andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM admin_account WHERE id = ?", String.class, targetId))
                .isEqualTo("테스트 관리자");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-010 로그인하지 않으면 401이다")
    void anonymous_unauthorized() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        mockMvc.perform(patch(UPDATE_URL, targetId)
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"김관리\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 수정도 취소된다(같은 트랜잭션)")
    void historyFails_rolledBack() throws Exception {
        long targetId = insertAdmin("kim01", "ADMIN", "ACTIVE");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(patch("/api/v1/admins/{id}", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"김관리\"}")
                        .with(csrf())
                        .with(AdminTestLogin.superAdmin("a".repeat(21))))
                .andExpect(status().isInternalServerError());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM admin_account WHERE id = ?", String.class, targetId))
                .isEqualTo("테스트 관리자");
        assertThat(count("work_history")).isZero();
    }

    private ResultActions update(long adminId, Map<String, String> body, Cookie session) throws Exception {
        return mockMvc.perform(patch(UPDATE_URL, adminId)
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

    private long insertAdmin(String loginId, String role, String adminStatus) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, email, phone, role, status, must_change_password)"
                        + " VALUES (?, ?, '테스트 관리자', 'kim@example.com', '010-1234-5678', ?, ?, 0)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                role,
                adminStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM admin_account WHERE login_id = ?", Long.class, loginId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }
}
