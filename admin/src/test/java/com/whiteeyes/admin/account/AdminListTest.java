package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 권한은 AdminTestUserConfig의 자동 관리자에 기대지 않고, 계정마다 로그인 화면(POST /login)으로 실제 로그인해 세션 쿠키로 부른다.
// 최근 활동은 실행 시각 기준이라 세션·로그인 시각을 지금으로부터 상대 시각으로 넣는다(ACTIVE_WINDOW 기본 30분)
/** 관리자 목록(FNC-A-003): 권한(최고 관리자만), 조회 조건, SPRING_SESSION_ADMIN 기준 최근 활동 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminListTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String ADMINS_URL = "/admin/api/v1/admins";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String PASSWORD = "test-password-1!";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        cleanTables();
        // DB 시각은 KST로 저장한다(앱 Clock과 같은 시간대)
        LocalDateTime now = LocalDateTime.now(KST);
        // 최고 관리자(로그인해 목록을 보는 계정), 관리자 3명(정상 2, 비활성화 1)
        insertAccount("super01", "최고 관리자", "SUPER_ADMIN", "ACTIVE", now.minusDays(30), null);
        insertAccount("admin01", "관리자 일", "ADMIN", "ACTIVE", now.minusDays(20), now.minusHours(5));
        insertAccount("admin02", "관리자 이", "ADMIN", "ACTIVE", now.minusDays(10), now.minusDays(2));
        insertAccount("off01", "꺼진 관리자", "ADMIN", "DISABLED", now.minusDays(5), now.minusDays(4));
        // admin01은 관리자 앱 세션이 10분 전까지 쓰였다 → 활동 중
        insertAdminSession("admin01", Instant.now().minus(10, ChronoUnit.MINUTES));
        // admin02는 같은 아이디의 사용자 앱 세션만 있다 → 관리자 활동으로 세지 않는다(마지막 로그인 2일 전, 활동 중 아님)
        insertUserSession("admin02", Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    @Test
    @DisplayName("FNC-A-003 로그인하지 않으면 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(api())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("FNC-A-003 일반 관리자로 로그인해 부르면 403")
    void admin_forbidden() throws Exception {
        Cookie session = login("admin01");

        mockMvc.perform(api().cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("FNC-A-003 최고 관리자로 로그인하면 200, 사용자 목록과 같은 형식에 role이 붙고 기본은 최근 생성 순")
    void superAdmin_ok() throws Exception {
        Cookie session = login("super01");

        String body = mockMvc.perform(api().cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.items[0].loginId").value("off01"))
                .andExpect(jsonPath("$.items[3].loginId").value("super01"))
                .andExpect(jsonPath("$.items[3].role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.items[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.items[0].status").value("DISABLED"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"name\":\"관리자 일\"", "\"email\":", "\"phone\":", "\"createdAt\":", "\"lastActiveAt\":")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("FNC-A-003 최근 활동은 SPRING_SESSION_ADMIN 기준, 같은 아이디의 사용자 앱 세션은 세지 않는다")
    void activity_fromAdminSessions() throws Exception {
        Cookie session = login("super01");

        // 활동 중: 방금 로그인한 super01과 10분 전 세션의 admin01
        mockMvc.perform(api().cookie(session).param("active", "true").param("sort", "loginId,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].loginId").value("admin01"))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.items[1].loginId").value("super01"));
        // admin02는 사용자 앱 세션(1분 전)이 있어도 관리자 기준 마지막 로그인 2일 전이라 활동 중 아님
        mockMvc.perform(api().cookie(session).param("active", "false").param("sort", "loginId,asc"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].loginId").value("admin02"))
                .andExpect(jsonPath("$.items[0].active").value(false))
                .andExpect(jsonPath("$.items[1].loginId").value("off01"));
    }

    @Test
    @DisplayName("FNC-A-003 검색어·상태로 거르고 최근 활동 순으로 정렬한다")
    void filtersAndSort() throws Exception {
        Cookie session = login("super01");

        mockMvc.perform(api().cookie(session).param("status", "DISABLED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].loginId").value("off01"));
        mockMvc.perform(api().cookie(session).param("keyword", "관리자 이"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].loginId").value("admin02"));
        // 최근 활동 최신 순: super01(지금) → admin01(10분 전 세션) → admin02(2일 전 로그인) → off01(4일 전 로그인)
        mockMvc.perform(api().cookie(session).param("sort", "lastActiveAt,desc"))
                .andExpect(jsonPath("$.items[0].loginId").value("super01"))
                .andExpect(jsonPath("$.items[1].loginId").value("admin01"))
                .andExpect(jsonPath("$.items[2].loginId").value("admin02"))
                .andExpect(jsonPath("$.items[3].loginId").value("off01"));
    }

    @Test
    @DisplayName("FNC-A-003 사용자 상태 값(PENDING)이나 잘못된 정렬은 400 INVALID_INPUT")
    void invalidInput() throws Exception {
        Cookie session = login("super01");

        mockMvc.perform(api().cookie(session).param("status", "PENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        mockMvc.perform(api().cookie(session).param("sort", "name,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    private MockHttpServletRequestBuilder api() {
        return get(ADMINS_URL).contextPath(CONTEXT_PATH);
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
                .getCookie(SESSION_COOKIE);
        assertThat(session).as(loginId + " 로그인 세션").isNotNull();
        return session;
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_USER");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private void insertAccount(
            String loginId, String name, String role, String status, LocalDateTime createdAt, LocalDateTime lastLoginAt) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, email, phone, role, status, must_change_password,"
                        + " last_login_at, created_at) VALUES (?, ?, ?, NULL, NULL, ?, ?, 0, ?, ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                name,
                role,
                status,
                lastLoginAt == null ? null : Timestamp.valueOf(lastLoginAt),
                Timestamp.valueOf(createdAt));
    }

    private void insertAdminSession(String loginId, Instant lastAccess) {
        insertSession("SPRING_SESSION_ADMIN", loginId, lastAccess);
    }

    private void insertUserSession(String loginId, Instant lastAccess) {
        insertSession("SPRING_SESSION_USER", loginId, lastAccess);
    }

    private void insertSession(String table, String loginId, Instant lastAccess) {
        jdbcTemplate.update(
                "INSERT INTO " + table + " (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,"
                        + " MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME) VALUES (?, ?, ?, ?, 1800, ?, ?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                lastAccess.toEpochMilli(),
                lastAccess.toEpochMilli(),
                lastAccess.plus(30, ChronoUnit.MINUTES).toEpochMilli(),
                loginId);
    }
}
