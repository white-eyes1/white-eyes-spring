package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 권한은 AdminTestUserConfig의 자동 관리자에 기대지 않고, 계정마다 로그인 화면(POST /login)으로 실제 로그인해 세션 쿠키로 부른다(#47과 같음)
/** 관리자 계정 생성(FNC-A-009): 권한, 저장 값, 임시 비밀번호, 작업 기록, 중복·입력 오류 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminCreateTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String ADMINS_URL = "/admin/api/v1/admins";
    private static final String PASSWORD = "test-password-1!";
    private static final String NEW_ADMIN_BODY =
            "{\"loginId\": \"admin09\", \"name\": \"김관리\", \"email\": \"kim@example.com\", \"phone\": \"010-1234-5678\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper jsonMapper;

    private long superId;

    @BeforeEach
    void setUp() {
        cleanTables();
        superId = insertAccount("super01", "SUPER_ADMIN", "ACTIVE");
        insertAccount("admin01", "ADMIN", "ACTIVE");
        insertAccount("off01", "ADMIN", "DISABLED");
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    @Test
    @DisplayName("FNC-A-009 로그인하지 않으면 401, 일반 관리자로 로그인하면 403이고 계정은 생기지 않는다")
    void unauthorizedAndForbidden() throws Exception {
        mockMvc.perform(create(NEW_ADMIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(create(NEW_ADMIN_BODY).cookie(login("admin01", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(countAccounts("admin09")).isZero();
    }

    @Test
    @DisplayName("FNC-A-009 최고 관리자로 로그인하면 201: ADMIN·ACTIVE·비밀번호 변경 강제·만든 사람으로 저장되고 임시 비밀번호는 bcrypt 값만 남는다")
    void superAdmin_creates() throws Exception {
        String body = mockMvc.perform(create(NEW_ADMIN_BODY).cookie(login("super01", PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = jsonMapper.readTree(body);
        long id = response.get("id").asLong();
        String temporaryPassword = response.get("temporaryPassword").asString();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM admin_account WHERE id = ?", id);
        assertThat(row.get("LOGIN_ID")).isEqualTo("admin09");
        assertThat(row.get("NAME")).isEqualTo("김관리");
        assertThat(row.get("EMAIL")).isEqualTo("kim@example.com");
        assertThat(row.get("PHONE")).isEqualTo("010-1234-5678");
        assertThat(row.get("ROLE")).isEqualTo("ADMIN");
        assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("MUST_CHANGE_PASSWORD")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("CREATED_BY")).longValue()).isEqualTo(superId);
        String stored = (String) row.get("PASSWORD");
        assertThat(stored).isNotEqualTo(temporaryPassword).startsWith("$2");
        assertThat(passwordEncoder.matches(temporaryPassword, stored)).isTrue();
        assertThat(temporaryPassword).hasSize(12);
    }

    @Test
    @DisplayName("FNC-A-009 만든 계정은 임시 비밀번호로 로그인되고 첫 로그인 때 비밀번호 변경 화면으로 간다")
    void createdAccount_mustChangePasswordOnFirstLogin() throws Exception {
        String body = mockMvc.perform(create(NEW_ADMIN_BODY).cookie(login("super01", PASSWORD)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String temporaryPassword = jsonMapper.readTree(body).get("temporaryPassword").asString();

        mockMvc.perform(loginRequest("admin09", temporaryPassword))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/me/password"));
    }

    @Test
    @DisplayName("FNC-A-009 작업 기록: ACCOUNT_CHANGE·ADMIN_ACCOUNT, 행동한 최고 관리자, 대상 아이디, detail에 비밀번호 값 없음")
    void workHistory() throws Exception {
        String body = mockMvc.perform(create(NEW_ADMIN_BODY).cookie(login("super01", PASSWORD)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = jsonMapper.readTree(body);
        long id = response.get("id").asLong();

        Map<String, Object> history = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(history.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) history.get("ACTOR_ID")).longValue()).isEqualTo(superId);
        assertThat(history.get("ACTOR_LOGIN_ID")).isEqualTo("super01");
        assertThat(history.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(history.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) history.get("TARGET_ID")).longValue()).isEqualTo(id);
        assertThat(history.get("TARGET_NAME")).isEqualTo("admin09");
        String detailText = new String((byte[]) history.get("DETAIL"), StandardCharsets.UTF_8);
        JsonNode detail = jsonMapper.readTree(detailText);
        assertThat(detail.get("op").asString()).isEqualTo("CREATE");
        assertThat(detail.get("role").asString()).isEqualTo("ADMIN");
        assertThat(detail.get("password").asString()).isEqualTo("발급됨");
        assertThat(detailText)
                .doesNotContain(response.get("temporaryPassword").asString())
                .doesNotContain("kim@example.com")
                .doesNotContain("010-1234-5678");
    }

    @Test
    @DisplayName("FNC-A-009 같은 아이디의 관리자(비활성화 포함)가 있으면 409 DUPLICATE_LOGIN_ID, 사용자 계정 아이디와는 겹쳐도 된다")
    void duplicateLoginId() throws Exception {
        Cookie session = login("super01", PASSWORD);

        mockMvc.perform(create("{\"loginId\": \"admin01\", \"name\": \"중복\"}").cookie(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"));
        mockMvc.perform(create("{\"loginId\": \"off01\", \"name\": \"중복\"}").cookie(session))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_history", Integer.class)).isZero();

        jdbcTemplate.update(
                "INSERT INTO user_account (login_id, password, name, status) VALUES ('user01', 'x', '사용자', 'ACTIVE')");
        mockMvc.perform(create("{\"loginId\": \"user01\", \"name\": \"관리자\"}").cookie(session))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("FNC-A-009 아이디·이름·메일·연락처 규칙을 어기면 400 INVALID_INPUT과 칸별 오류, 메일·연락처를 비우면 NULL로 저장")
    void validation() throws Exception {
        Cookie session = login("super01", PASSWORD);

        mockMvc.perform(create(
                        "{\"loginId\": \"Admin\", \"name\": \" \", \"email\": \"not-mail\", \"phone\": \"010-abcd\"}")
                        .cookie(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(4));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class)).isEqualTo(3);

        mockMvc.perform(create("{\"loginId\": \"admin10\", \"name\": \"선택 없음\", \"email\": \"\", \"phone\": \"\"}")
                        .cookie(session))
                .andExpect(status().isCreated());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT email, phone FROM admin_account WHERE login_id = 'admin10'");
        assertThat(row.get("EMAIL")).isNull();
        assertThat(row.get("PHONE")).isNull();
    }

    private MockHttpServletRequestBuilder create(String json) {
        return post(ADMINS_URL)
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf());
    }

    private MockHttpServletRequestBuilder loginRequest(String loginId, String password) {
        return post("/admin/login")
                .contextPath(CONTEXT_PATH)
                .param("username", loginId)
                .param("password", password)
                .with(csrf());
    }

    private Cookie login(String loginId, String password) throws Exception {
        Cookie session = mockMvc.perform(loginRequest(loginId, password))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
        assertThat(session).as(loginId + " 로그인 세션").isNotNull();
        return session;
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private long countAccounts(String loginId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_account WHERE login_id = ?", Long.class, loginId);
    }

    private long insertAccount(String loginId, String role, String status) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '테스트 관리자', ?, ?, 0)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                role,
                status);
        return jdbcTemplate.queryForObject("SELECT id FROM admin_account WHERE login_id = ?", Long.class, loginId);
    }
}
