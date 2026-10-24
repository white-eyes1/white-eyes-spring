package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 실제 서버를 띄워 HTTP로 로그인한다(FNC-C-001). 쿠키 이름은 내장 서버가 있어야 적용된다.
 * 검수 기준 "두 앱에 동시에 로그인해도 서로 세션이 덮어써지지 않음" 중 사용자 앱 쪽: 사용자 앱은 USER_SESSION 쿠키만 발급·사용하고
 * 같은 브라우저가 함께 보내는 관리자 앱 쿠키(ADMIN_SESSION)는 읽지 않는다. 관리자 앱 쪽은 admin AdminLoginCookieTest가 본다.
 * 테스트용 application.yml이 main의 것을 가리므로 쿠키 이름은 main과 같은 값을 여기서 준다(UserAppSessionTest가 main 값을 확인).
 * 세션 표 이름은 테스트 H2에 Spring Session 기본 표(SPRING_SESSION)만 있어 바꾸지 않는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.name=USER_SESSION")
class UserLoginCookieTest {

    private static final String PASSWORD = "test-password-1!";
    private static final String COOKIE = "USER_SESSION";
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        userAccountRepository.deleteAll();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithCookie(String path, String cookieHeader) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Cookie", cookieHeader)
                .GET()
                .build());
    }

    private String cookieHeader(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .map(value -> value.substring(0, value.indexOf(';') < 0 ? value.length() : value.indexOf(';')))
                .orElse(null);
    }

    private record LoginResult(String preLoginCookie, HttpResponse<String> response) {}

    // 로그인 화면에서 CSRF 토큰과 로그인 전 세션 쿠키를 받아 폼 로그인한다
    private LoginResult login(String loginId) throws Exception {
        HttpResponse<String> loginPage = send(HttpRequest.newBuilder(URI.create(base() + "/login")).GET().build());
        assertThat(loginPage.statusCode()).isEqualTo(200);
        Matcher csrf = CSRF_FIELD.matcher(loginPage.body());
        assertThat(csrf.find()).as("로그인 화면의 CSRF 토큰").isTrue();
        String preLoginCookie = cookieHeader(loginPage, COOKIE);
        assertThat(preLoginCookie).as("로그인 화면이 발급한 세션 쿠키").isNotNull();

        String form = "username=" + loginId + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base() + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", preLoginCookie)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
        return new LoginResult(preLoginCookie, response);
    }

    private void insertActiveAccount(String loginId) {
        Long id = userAccountRepository
                .save(UserAccount.createPending(loginId, passwordEncoder.encode(PASSWORD), "쿠키 사용자", null, null))
                .getId();
        jdbcTemplate.update("UPDATE user_account SET status = 'ACTIVE' WHERE id = ?", id);
    }

    @Test
    @DisplayName("FNC-C-001 사용자 앱 로그인은 USER_SESSION 쿠키만 발급하고 로그인 전 세션 번호는 버린다")
    void login_issuesUserSessionCookie() throws Exception {
        insertActiveAccount("cookie01");
        LoginResult result = login("cookie01");
        HttpResponse<String> login = result.response();

        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElse("")).endsWith("/stats");
        assertThat(login.headers().allValues("Set-Cookie"))
                .noneMatch(value -> value.startsWith("SESSION=") || value.startsWith("ADMIN_SESSION="));
        assertThat(login.headers().allValues("Set-Cookie"))
                .filteredOn(value -> value.startsWith(COOKIE + "="))
                .isNotEmpty()
                .allMatch(value -> value.contains("HttpOnly"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'cookie01'", Integer.class))
                .isEqualTo(1);

        // 세션 고정 방어: 로그인 전에 받은 세션 번호는 로그인 뒤 쓰이지 않는다
        String loginCookie = cookieHeader(login, COOKIE);
        assertThat(loginCookie).isNotNull().isNotEqualTo(result.preLoginCookie());
    }

    @Test
    @DisplayName("FNC-C-001 같은 브라우저가 관리자 앱 쿠키(ADMIN_SESSION)를 함께 보내도 사용자 앱 세션은 그대로 쓰인다")
    void adminCookieAlongside_userSessionKept() throws Exception {
        insertActiveAccount("cookie02");
        String loginCookie = cookieHeader(login("cookie02").response(), COOKIE);
        String sessionValue = loginCookie.substring((COOKIE + "=").length());

        HttpResponse<String> both = getWithCookie("/me/password", "ADMIN_SESSION=" + sessionValue + "x; " + loginCookie);
        assertThat(both.statusCode()).as("관리자 앱 쿠키와 함께 보낸 사용자 앱 세션").isEqualTo(200);
        assertThat(cookieHeader(both, "ADMIN_SESSION")).as("관리자 앱 쿠키를 건드리지 않음").isNull();
    }

    @Test
    @DisplayName("FNC-C-001 사용자 앱은 ADMIN_SESSION 쿠키를 자기 세션으로 읽지 않는다")
    void adminCookieOnly_notLoggedIn() throws Exception {
        insertActiveAccount("cookie03");
        String loginCookie = cookieHeader(login("cookie03").response(), COOKIE);
        String sessionValue = loginCookie.substring((COOKIE + "=").length());

        HttpResponse<String> adminOnly = getWithCookie("/me/password", "ADMIN_SESSION=" + sessionValue);

        assertThat(adminOnly.statusCode()).isEqualTo(302);
        assertThat(adminOnly.headers().firstValue("Location").orElse("")).endsWith("/login");
    }
}
