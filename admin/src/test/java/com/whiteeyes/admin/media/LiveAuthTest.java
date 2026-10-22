package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.security.AccountPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 실시간 화면·미리보기 권한 확인(FNC-A-041, nginx auth_request).
 * 검수 기준: 사용자 계정 쿠키로 요청 시 거부 — 사용자 앱의 쿠키·USER 역할 세션은 열어 주지 않는다.
 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class LiveAuthTest {

    private static final String PASSWORD = "test-password-1!";
    private static final String LOGIN_ID = "live-admin";
    private static final String AUTH_URL = "/admin/api/v1/live/auth";
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    private long runningCameraId;
    private long stoppedCameraId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999993', '실시간 권한 테스트동', '{}', 37.600000, 127.002000)");
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999993'", Long.class);
        runningCameraId = insertCamera(dongId, "가동 구역", "RUNNING");
        stoppedCameraId = insertCamera(dongId, "정지 구역", "STOPPED");
    }

    // camera·zone·region·세션·계정을 남기면 뒤에 도는 다른 테스트가 지우다 FK로 실패하거나 세션 수가 어긋난다
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("DELETE FROM region WHERE code = '99999993'");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    @DisplayName("FNC-A-041 실제 관리자 로그인 세션 쿠키로 가동 중인 캠을 확인하면 204")
    void adminSession_runningCamera_noContent() throws Exception {
        Cookie session = adminLoginCookie();

        check(runningCameraId, session).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("FNC-A-041 SUPER_ADMIN 세션도 204")
    void superAdminSession_noContent() throws Exception {
        check(runningCameraId, sessionCookie("ROLE_SUPER_ADMIN")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("FNC-A-041 정지된 캠·없는 캠·삭제된 캠·cameraId 없음·숫자 아님은 403")
    void notRunningCamera_forbidden() throws Exception {
        Cookie session = adminLoginCookie();
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", stoppedCameraId + 1000);

        check(stoppedCameraId, session).andExpect(status().isForbidden());
        check(999999L, session).andExpect(status().isForbidden());
        mockMvc.perform(get(AUTH_URL).contextPath("/admin").cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(get(AUTH_URL).contextPath("/admin").param("cameraId", "abc").cookie(session))
                .andExpect(status().isForbidden());

        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", runningCameraId);
        check(runningCameraId, session).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FNC-A-041 쿠키가 없거나 알 수 없는 세션이면 401")
    void noOrUnknownSession_unauthorized() throws Exception {
        check(runningCameraId, null).andExpect(status().isUnauthorized());
        check(runningCameraId, new Cookie(SESSION_COOKIE, "!!!not-base64!!!")).andExpect(status().isUnauthorized());
        // 사용자 앱 등 다른 저장소의 세션 번호는 이 저장소에 없다
        String foreignId = Base64.getEncoder().encodeToString("some-other-app-session".getBytes(StandardCharsets.UTF_8));
        check(runningCameraId, new Cookie(SESSION_COOKIE, foreignId)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-041 사용자(USER) 역할 세션은 거부(403)")
    void userRoleSession_forbidden() throws Exception {
        check(runningCameraId, sessionCookie("ROLE_USER")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FNC-A-041 만료된 세션은 401")
    void expiredSession_unauthorized() throws Exception {
        Cookie session = sessionCookie("ROLE_ADMIN");
        jdbcTemplate.update("UPDATE SPRING_SESSION_ADMIN SET LAST_ACCESS_TIME = 1, EXPIRY_TIME = 1");

        check(runningCameraId, session).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-041 확인을 아무리 해도 세션 마지막 접근 시각·만료 시각이 늘지 않고 새 세션·쿠키도 생기지 않는다")
    void check_doesNotRefreshSession() throws Exception {
        Cookie session = sessionCookie("ROLE_ADMIN");
        Map<String, Object> before = jdbcTemplate.queryForMap("SELECT LAST_ACCESS_TIME, EXPIRY_TIME FROM SPRING_SESSION_ADMIN");
        long sessionRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN", Long.class);

        for (int i = 0; i < 5; i++) {
            check(runningCameraId, session)
                    .andExpect(status().isNoContent())
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        check(runningCameraId, null).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));

        Map<String, Object> after = jdbcTemplate.queryForMap("SELECT LAST_ACCESS_TIME, EXPIRY_TIME FROM SPRING_SESSION_ADMIN");
        assertThat(after).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN", Long.class)).isEqualTo(sessionRows);
    }

    private ResultActions check(long cameraId, Cookie session) throws Exception {
        var request = get(AUTH_URL).contextPath("/admin").param("cameraId", String.valueOf(cameraId));
        return mockMvc.perform(session == null ? request : request.cookie(session));
    }

    // 로그인 화면으로 실제 로그인해 받은 쿠키
    private Cookie adminLoginCookie() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '실시간 권한 테스트', 'ADMIN', 'ACTIVE', 0)",
                LOGIN_ID,
                passwordEncoder.encode(PASSWORD));
        Cookie session = mockMvc.perform(post("/admin/login")
                        .contextPath("/admin")
                        .param("username", LOGIN_ID)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);
        assertThat(session).as("로그인 뒤 세션 쿠키").isNotNull();
        return session;
    }

    // 지정한 역할로 로그인한 것처럼 세션을 저장소에 직접 만들고, 브라우저가 보낼 쿠키(세션 번호 Base64)를 돌려준다
    @SuppressWarnings("unchecked")
    private Cookie sessionCookie(String authority) {
        AccountPrincipal principal =
                new AccountPrincipal(1L, "someone", "encoded", true, false, List.of(new SimpleGrantedAuthority(authority)));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(authority)));
        Session session = sessionRepository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        // JdbcSession 클래스가 비공개라 저장은 인터페이스(SessionRepository)로 한다
        SessionRepository<Session> repository = (SessionRepository<Session>) (SessionRepository<?>) sessionRepository;
        repository.save(session);
        return new Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private long insertCamera(long dongId, String zoneName, String status) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000)",
                dongId,
                zoneName);
        long zoneId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', ?)",
                zoneId,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }
}
