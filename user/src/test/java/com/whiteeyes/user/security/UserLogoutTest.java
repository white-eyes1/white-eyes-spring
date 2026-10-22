package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.security.AccountPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 사용자 앱 로그아웃(FNC-C-002). 사용자 앱 로그인이 아직 없어(이슈 #126) 세션을 저장소에 직접 만들어 확인한다.
 * "로그아웃 뒤 이전 화면 주소 → 로그인 화면"은 사용자 앱 권한 표(#126)가 있어야 확인할 수 있어 여기서는 세션 삭제까지만 본다.
 */
// 사용자 앱은 Flyway를 돌리지 않아(관리자 앱만 실행) 테스트 H2의 세션 표는 Spring Session이 기본 이름(SPRING_SESSION)으로 만든다
@SpringBootTest
@AutoConfigureMockMvc
class UserLogoutTest {

    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ROLE_USER", "ROLE_PENDING"})
    @DisplayName("FNC-C-002 로그아웃하면 /login으로 이동하고 세션 행이 지워진다")
    void logout_redirectsToLoginAndDeletesSession(String authority) throws Exception {
        Cookie session = sessionCookie(authority);
        assertThat(sessionRows()).isEqualTo(1);

        mockMvc.perform(post("/logout").cookie(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));

        assertThat(sessionRows()).isZero();
    }

    @Test
    @DisplayName("FNC-C-002 세션이 이미 없으면(쿠키 없음·모르는 세션·만료) CSRF 토큰이 없어도 로그인 화면으로 이동")
    void logout_withoutSession_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/logout")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));

        String unknownId = Base64.getEncoder().encodeToString("unknown-session".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/logout").cookie(new Cookie(SESSION_COOKIE, unknownId)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));

        Cookie session = sessionCookie("ROLE_USER");
        jdbcTemplate.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = 1, EXPIRY_TIME = 1");
        mockMvc.perform(post("/logout").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("FNC-C-002 세션이 있는데 CSRF 토큰이 없으면 403이고 세션은 남는다")
    void logout_withSessionButNoCsrfToken_forbidden() throws Exception {
        Cookie session = sessionCookie("ROLE_USER");

        mockMvc.perform(post("/logout").cookie(session)).andExpect(status().isForbidden());

        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-C-002 CSRF 검사를 건너뛰는 것은 세션 없는 POST /logout뿐이다(세션 없는 다른 POST는 403)")
    void csrfSkip_onlyForLogout() throws Exception {
        mockMvc.perform(post("/api/v1/anything")).andExpect(status().isForbidden());
        mockMvc.perform(post("/logout/other")).andExpect(status().isForbidden());
    }

    // 지정한 권한으로 로그인한 것처럼 세션을 저장소에 직접 만들고, 브라우저가 보낼 쿠키(세션 번호 Base64)를 돌려준다
    @SuppressWarnings("unchecked")
    private Cookie sessionCookie(String authority) {
        var authorities = List.of(new SimpleGrantedAuthority(authority));
        var principal = new AccountPrincipal(1L, "someone", "encoded", true, false, authorities);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        Session session = sessionRepository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        // JdbcSession 클래스가 비공개라 저장은 인터페이스(SessionRepository)로 한다
        ((SessionRepository<Session>) (SessionRepository<?>) sessionRepository).save(session);
        return new Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private long sessionRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);
    }
}
