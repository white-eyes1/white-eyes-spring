package com.whiteeyes.user.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.security.AccountPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 사용자 앱 내 정보 조회·수정(FNC-C-006). 세션을 저장소에 직접 만들어 요청한다(UserPasswordChangeTest와 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserMyInfoTest {

    private static final String SESSION_COOKIE = "SESSION";
    private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";
    private static final String LOGIN_ID = "user01";
    private static final String OTHER_LOGIN_ID = "user02";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    private Long accountId;
    private Long otherId;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        userAccountRepository.deleteAll();
    }

    @BeforeEach
    void insertAccounts() {
        cleanUp();
        accountId = insert(LOGIN_ID, "홍길동", "hong@example.com", "010-1234-5678");
        otherId = insert(OTHER_LOGIN_ID, "다른사람", "other@example.com", "010-0000-0000");
    }

    private Long insert(String loginId, String name, String email, String phone) {
        Long id = userAccountRepository
                .save(UserAccount.createPending(loginId, "encoded-not-used", name, email, phone))
                .getId();
        jdbcTemplate.update("UPDATE user_account SET status = 'ACTIVE' WHERE id = ?", id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private Cookie sessionCookie(String role, boolean mustChangePassword) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = new AccountPrincipal(accountId, LOGIN_ID, "encoded", true, mustChangePassword, authorities);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        Session session = sessionRepository.createSession();
        session.setAttribute(SECURITY_CONTEXT, new SecurityContextImpl(authentication));
        ((SessionRepository<Session>) (SessionRepository<?>) sessionRepository).save(session);
        return new Cookie(
                SESSION_COOKIE, Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions getMe(Cookie session) throws Exception {
        return mockMvc.perform(get("/api/v1/me").cookie(session));
    }

    private ResultActions patchMe(Cookie session, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/me")
                .cookie(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String column(Long id, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM user_account WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 내 정보를 조회하면 role 없이 본인 정보가 나온다")
    void get_returnsOwnInfoWithoutRole() throws Exception {
        getMe(sessionCookie("ROLE_USER", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.email").value("hong@example.com"))
                .andExpect(jsonPath("$.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 수정하면 응답과 다시 조회한 값, DB에 반영된다")
    void patch_updatesAndReflectsOnRead() throws Exception {
        Cookie session = sessionCookie("ROLE_USER", false);

        patchMe(session, """
                {"name":"김철수","email":"kim@example.com","phone":"02-123-4567"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김철수"));

        getMe(session)
                .andExpect(jsonPath("$.name").value("김철수"))
                .andExpect(jsonPath("$.email").value("kim@example.com"))
                .andExpect(jsonPath("$.phone").value("02-123-4567"));
        assertThat(column(accountId, "name")).isEqualTo("김철수");
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 보낸 필드만 고치고 나머지는 그대로다")
    void patch_onlySentFields() throws Exception {
        patchMe(sessionCookie("ROLE_USER", false), """
                {"phone":"010-9999-9999"}""")
                .andExpect(status().isOk());

        assertThat(column(accountId, "name")).isEqualTo("홍길동");
        assertThat(column(accountId, "email")).isEqualTo("hong@example.com");
        assertThat(column(accountId, "phone")).isEqualTo("010-9999-9999");
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 메일·연락처를 빈 문자열로 보내면 비운다")
    void patch_emptyString_clears() throws Exception {
        patchMe(sessionCookie("ROLE_USER", false), """
                {"email":"","phone":""}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());

        assertThat(column(accountId, "email")).isNull();
        assertThat(column(accountId, "phone")).isNull();
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 검증에 실패하면 400 fieldErrors이고 저장하지 않는다")
    void patch_invalid_returnsFieldErrors() throws Exception {
        patchMe(sessionCookie("ROLE_USER", false), """
                {"name":"","email":"not-an-email","phone":"abc"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));

        assertThat(column(accountId, "name")).isEqualTo("홍길동");
        assertThat(column(accountId, "email")).isEqualTo("hong@example.com");
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 아이디·상태·비밀번호 변경 강제 값을 보내도 바뀌지 않는다")
    void patch_cannotChangeLoginIdOrStatus() throws Exception {
        patchMe(sessionCookie("ROLE_USER", false), """
                {"name":"홍길동2","loginId":"hacker","status":"WITHDRAWN","role":"SUPER_ADMIN","mustChangePassword":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        assertThat(column(accountId, "login_id")).isEqualTo(LOGIN_ID);
        assertThat(column(accountId, "status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 다른 계정의 정보는 바뀌지 않는다")
    void patch_doesNotTouchOtherAccounts() throws Exception {
        patchMe(sessionCookie("ROLE_USER", false), """
                {"name":"김철수","email":"kim@example.com","phone":"010-1"}""")
                .andExpect(status().isOk());

        assertThat(column(otherId, "name")).isEqualTo("다른사람");
        assertThat(column(otherId, "email")).isEqualTo("other@example.com");
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 로그인하지 않으면 조회·수정 모두 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-C-006 사용자 앱: 승인 대기(PENDING) 계정은 403")
    void pending_isForbidden() throws Exception {
        getMe(sessionCookie("ROLE_PENDING", false)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FNC-C-003·006 사용자 앱: 비밀번호 변경이 강제된 계정은 내 정보 API가 403")
    void forcedPasswordChange_isForbidden() throws Exception {
        getMe(sessionCookie("ROLE_USER", true)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SCR-C-002 사용자 앱: /me 화면에 현재 값이 채워질 입력 칸과 비밀번호 영역이 있다")
    void mePage_rendersProfileAndPasswordArea() throws Exception {
        mockMvc.perform(get("/me").cookie(sessionCookie("ROLE_USER", false)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"profile-form\"")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"phone\"")))
                .andExpect(content().string(containsString("id=\"password-form\"")));
    }
}
