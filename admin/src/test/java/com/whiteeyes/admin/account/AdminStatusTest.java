package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.security.AccountPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 권한은 계정마다 로그인 화면(POST /login)으로 실제 로그인한 세션 쿠키로 확인한다(#47과 같음)
/** 관리자 비활성화·활성화(FNC-A-011): 상태 변경, 세션 삭제, 최고 관리자 불가, 409, 권한, 작업 기록 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminStatusTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String DISABLE_URL = "/admin/api/v1/admins/{id}/disable";
    private static final String ENABLE_URL = "/admin/api/v1/admins/{id}/enable";
    private static final String PASSWORD = "test-password-1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long superId;
    private long adminId;
    private long otherAdminId;
    private long disabledId;

    @BeforeEach
    void setUp() {
        cleanTables();
        superId = insertAccount("super01", "SUPER_ADMIN", "ACTIVE");
        adminId = insertAccount("admin01", "ADMIN", "ACTIVE");
        otherAdminId = insertAccount("admin02", "ADMIN", "ACTIVE");
        disabledId = insertAccount("off01", "ADMIN", "DISABLED");
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    // ---- 비활성화 ----

    @Test
    @DisplayName("FNC-A-011 ACTIVE 관리자를 비활성화하면 204이고 DISABLED가 된다")
    void disable_active_becomesDisabled() throws Exception {
        disable(adminId, login("super01")).andExpect(status().isNoContent());

        assertThat(statusOf(adminId)).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("FNC-A-011 검수 기준: 비활성화하면 대상 세션이 바로 끝나 다음 요청은 로그인 화면으로 가고, 다시 로그인할 수 없다")
    void disable_endsTargetSessionsImmediately() throws Exception {
        Cookie target = login("admin01");
        Cookie other = login("admin02");
        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(target)).andExpect(status().isOk());

        disable(adminId, login("super01")).andExpect(status().isNoContent());

        assertThat(sessionsOf("admin01")).isZero();
        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(target))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get("/admin/stats").contextPath(CONTEXT_PATH).cookie(other)).andExpect(status().isOk());
        mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", "admin01")
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error")));
    }

    @Test
    @DisplayName("FNC-A-011 비활성화는 work_history에 ACCOUNT_CHANGE 1행(ADMIN_ACCOUNT, status: ACTIVE→DISABLED)을 남긴다")
    void disable_recordsAccountChange() throws Exception {
        disable(adminId, login("super01")).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(superId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("super01");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("admin01");
        assertThat(detail(row).get("status").asString()).isEqualTo("ACTIVE→DISABLED");
    }

    @Test
    @DisplayName("FNC-A-011 이미 DISABLED면 409 INVALID_STATUS이고 상태도 기록도 그대로다")
    void disable_alreadyDisabled_conflict() throws Exception {
        disable(disabledId, login("super01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(statusOf(disabledId)).isEqualTo("DISABLED");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-011 같은 관리자를 두 번 비활성화하면 두 번째는 409이고 기록은 1행이다")
    void disable_twice_secondConflict() throws Exception {
        Cookie session = login("super01");

        disable(adminId, session).andExpect(status().isNoContent());
        disable(adminId, session).andExpect(status().isConflict());

        assertThat(count("work_history")).isOne();
    }

    @Test
    @DisplayName("FNC-A-011 최고 관리자 계정은 대상 불가(400 INVALID_INPUT)이고 자기 자신도 마찬가지다")
    void disable_superAdmin_badRequest() throws Exception {
        long otherSuperId = insertAccount("super02", "SUPER_ADMIN", "ACTIVE");
        Cookie session = login("super01");

        disable(otherSuperId, session)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        disable(superId, session).andExpect(status().isBadRequest());

        assertThat(statusOf(otherSuperId)).isEqualTo("ACTIVE");
        assertThat(statusOf(superId)).isEqualTo("ACTIVE");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-011 없는 관리자는 404 NOT_FOUND이고 기록이 없다")
    void disable_unknown_notFound() throws Exception {
        disable(999_999L, login("super01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-011 일반 관리자는 비활성화할 수 없다(403). 로그인하지 않으면 401")
    void disable_forbiddenAndUnauthorized() throws Exception {
        disable(otherAdminId, login("admin01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post(DISABLE_URL, otherAdminId).contextPath(CONTEXT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(otherAdminId)).isEqualTo("ACTIVE");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 상태 변경과 세션 삭제도 취소된다(같은 트랜잭션)")
    void disable_historyFails_rolledBack() throws Exception {
        login("admin01");

        // 행동한 계정 아이디가 work_history 칸(20자)보다 길면 기록 저장이 실패한다
        mockMvc.perform(post("/api/v1/admins/{id}/disable", adminId)
                        .with(csrf())
                        .with(authentication(superAdminToken("a".repeat(21)))))
                .andExpect(status().isInternalServerError());

        assertThat(statusOf(adminId)).isEqualTo("ACTIVE");
        assertThat(sessionsOf("admin01")).as("세션 삭제도 취소").isOne();
        assertThat(count("work_history")).isZero();
    }

    // ---- 활성화 ----

    @Test
    @DisplayName("FNC-A-011 DISABLED 관리자를 활성화하면 204이고 ACTIVE가 되어 다시 로그인할 수 있다")
    void enable_disabled_becomesActive() throws Exception {
        enable(disabledId, login("super01")).andExpect(status().isNoContent());

        assertThat(statusOf(disabledId)).isEqualTo("ACTIVE");
        mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", "off01")
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/stats"));
    }

    @Test
    @DisplayName("FNC-A-011 활성화는 work_history에 ACCOUNT_CHANGE 1행(status: DISABLED→ACTIVE)을 남긴다")
    void enable_recordsAccountChange() throws Exception {
        enable(disabledId, login("super01")).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(disabledId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("off01");
        assertThat(detail(row).get("status").asString()).isEqualTo("DISABLED→ACTIVE");
    }

    @Test
    @DisplayName("FNC-A-011 이미 ACTIVE면 활성화는 409 INVALID_STATUS이고 기록이 없다")
    void enable_alreadyActive_conflict() throws Exception {
        enable(adminId, login("super01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-011 없는 관리자 활성화는 404, 일반 관리자는 403, 로그인하지 않으면 401")
    void enable_notFoundForbiddenUnauthorized() throws Exception {
        enable(999_999L, login("super01")).andExpect(status().isNotFound());
        enable(disabledId, login("admin01")).andExpect(status().isForbidden());
        mockMvc.perform(post(ENABLE_URL, disabledId).contextPath(CONTEXT_PATH).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(disabledId)).isEqualTo("DISABLED");
    }

    private ResultActions disable(long targetId, Cookie session) throws Exception {
        return mockMvc.perform(post(DISABLE_URL, targetId).contextPath(CONTEXT_PATH).cookie(session).with(csrf()));
    }

    private ResultActions enable(long targetId, Cookie session) throws Exception {
        return mockMvc.perform(post(ENABLE_URL, targetId).contextPath(CONTEXT_PATH).cookie(session).with(csrf()));
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

    private UsernamePasswordAuthenticationToken superAdminToken(String loginId) {
        AccountPrincipal principal = new AccountPrincipal(
                superId, loginId, "encoded", true, false, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private long insertAccount(String loginId, String role, String accountStatus) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '테스트 관리자', ?, ?, 0)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                role,
                accountStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM admin_account WHERE login_id = ?", Long.class, loginId);
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM admin_account WHERE id = ?", String.class, id);
    }

    private long sessionsOf(String principalName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION_ADMIN WHERE PRINCIPAL_NAME = ?", Long.class, principalName);
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
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }
}
