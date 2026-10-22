package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 앱 비밀번호 변경(FNC-C-007). 실제 로그인으로 만든 세션 쿠키로 요청해, 강제 변경 흐름과
 * 다른 세션 삭제, 변경 뒤 세션의 로그인 정보 갱신(이슈 #4에서 넘겨받은 일)을 확인한다.
 */
// 테스트용 application.yml이 main의 것을 가리므로 세션 테이블 이름은 main과 같은 값을 여기서 준다(AdminLoginTest와 같음)
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminPasswordChangeTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String LOGIN_ID = "admin01";
    private static final String CURRENT = "test-password-1!";
    private static final String NEW = "new-password-2@";
    private static final String FORCED_NOTICE = "임시 비밀번호로 로그인했습니다. 새 비밀번호를 설정하세요.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private void insertAdmin(boolean mustChangePassword) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', ?)",
                LOGIN_ID,
                passwordEncoder.encode(CURRENT),
                "테스트 관리자",
                mustChangePassword);
    }

    private Cookie login(String password) throws Exception {
        Cookie cookie = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", LOGIN_ID)
                        .param("password", password)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);
        assertThat(cookie).as("로그인 세션 쿠키").isNotNull();
        return cookie;
    }

    private ResultActions changePassword(Cookie session, String current, String newPassword, String confirm)
            throws Exception {
        String body = """
                {"currentPassword":"%s","newPassword":"%s","newPasswordConfirm":"%s"}"""
                .formatted(current, newPassword, confirm);
        return mockMvc.perform(patch("/admin/api/v1/me/password")
                .contextPath(CONTEXT_PATH)
                .cookie(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getPage(Cookie session, String path) throws Exception {
        return mockMvc.perform(get("/admin" + path).contextPath(CONTEXT_PATH).cookie(session));
    }

    private boolean mustChangePasswordInDb() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT must_change_password FROM admin_account WHERE login_id = ?", Boolean.class, LOGIN_ID));
    }

    private String storedPassword() {
        return jdbcTemplate.queryForObject(
                "SELECT password FROM admin_account WHERE login_id = ?", String.class, LOGIN_ID);
    }

    private long sessionRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN", Long.class);
    }

    @Test
    @DisplayName("FNC-C-007 첫 로그인 강제 변경: 변경 화면에 갇혔다가 변경하면 같은 세션으로 통계 메인에 들어간다")
    void forcedChange_unlocksSameSession() throws Exception {
        insertAdmin(true);
        Cookie session = login(CURRENT);

        getPage(session, "/stats").andExpect(status().isFound()).andExpect(redirectedUrl("/admin/me/password"));
        getPage(session, "/me/password").andExpect(status().isOk()).andExpect(content().string(containsString(FORCED_NOTICE)))
                // 강제 상태에서는 로그아웃만 보이고 다른 메뉴는 숨긴다(SCR-C-002)
                .andExpect(content().string(containsString("data-header=\"forced\"")))
                .andExpect(content().string(not(containsString("data-header=\"admin\""))))
                .andExpect(content().string(not(containsString("이벤트 로그"))));

        changePassword(session, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        assertThat(mustChangePasswordInDb()).isFalse();
        assertThat(passwordEncoder.matches(NEW, storedPassword())).isTrue();
        // 변경한 계정이 계속 변경 화면에 갇히지 않는다(#4에서 넘겨받은 일)
        getPage(session, "/stats").andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-007 변경 뒤에는 새 비밀번호로만 로그인된다")
    void afterChange_onlyNewPasswordLogsIn() throws Exception {
        insertAdmin(false);
        Cookie session = login(CURRENT);

        changePassword(session, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", LOGIN_ID)
                        .param("password", CURRENT)
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/login?error"));
        mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", LOGIN_ID)
                        .param("password", NEW)
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/stats"));
    }

    @Test
    @DisplayName("FNC-C-007 다른 브라우저의 세션은 끊기고 변경한 세션은 유지된다")
    void change_endsOtherSessions() throws Exception {
        insertAdmin(false);
        Cookie changing = login(CURRENT);
        Cookie other = login(CURRENT);
        assertThat(sessionRows()).isEqualTo(2);
        getPage(other, "/stats").andExpect(status().isOk());

        changePassword(changing, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        assertThat(sessionRows()).isEqualTo(1);
        getPage(other, "/stats").andExpect(status().isFound()).andExpect(redirectedUrl("/admin/login"));
        getPage(changing, "/stats").andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-007 다른 계정의 세션은 건드리지 않는다")
    void change_keepsOtherAccountsSessions() throws Exception {
        insertAdmin(false);
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES ('admin02', ?, '다른 관리자', 'ADMIN', 'ACTIVE', FALSE)",
                passwordEncoder.encode(CURRENT));
        Cookie changing = login(CURRENT);
        Cookie otherAccount = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", "admin02")
                        .param("password", CURRENT)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);

        changePassword(changing, CURRENT, NEW, NEW).andExpect(status().isNoContent());

        getPage(otherAccount, "/stats").andExpect(status().isOk());
        assertThat(sessionRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-C-007 현재 비밀번호가 틀리면 400 WRONG_PASSWORD이고 비밀번호·세션은 그대로다")
    void wrongCurrent_rejected() throws Exception {
        insertAdmin(false);
        Cookie changing = login(CURRENT);
        login(CURRENT);
        String before = storedPassword();

        changePassword(changing, "wrong-pass9!", NEW, NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        assertThat(storedPassword()).isEqualTo(before);
        assertThat(sessionRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-C-007 규칙 위반은 400이고 칸별 오류가 담기며 아무것도 바꾸지 않는다")
    void invalidInput_fieldErrors() throws Exception {
        insertAdmin(false);
        Cookie session = login(CURRENT);
        String before = storedPassword();

        changePassword(session, CURRENT, "short1", "short1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        changePassword(session, CURRENT, NEW, "different-pass3#")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPasswordConfirm"));
        changePassword(session, CURRENT, CURRENT, CURRENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));

        assertThat(storedPassword()).isEqualTo(before);
    }

    @Test
    @DisplayName("FNC-C-007 로그인하지 않았으면 401, CSRF 토큰이 없으면 403이다")
    void withoutLoginOrCsrf_rejected() throws Exception {
        insertAdmin(false);
        Cookie session = login(CURRENT);
        String body = """
                {"currentPassword":"%s","newPassword":"%s","newPasswordConfirm":"%s"}"""
                .formatted(CURRENT, NEW, NEW);

        mockMvc.perform(patch("/admin/api/v1/me/password")
                        .contextPath(CONTEXT_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/admin/api/v1/me/password")
                        .contextPath(CONTEXT_PATH)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        assertThat(passwordEncoder.matches(CURRENT, storedPassword())).isTrue();
    }

    @Test
    @DisplayName("FNC-C-007 강제 상태가 아닐 때도 변경 화면이 열리고 강제 안내 문구는 없다")
    void passwordPage_notForced_noNotice() throws Exception {
        insertAdmin(false);
        Cookie session = login(CURRENT);

        getPage(session, "/me/password")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(FORCED_NOTICE))))
                .andExpect(content().string(containsString("data-header=\"admin\"")))
                .andExpect(content().string(containsString("비밀번호 변경")));
    }
}
