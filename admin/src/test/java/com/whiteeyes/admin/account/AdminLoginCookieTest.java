package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 실제 서버를 띄워 HTTP로 로그인한다(FNC-C-001). 쿠키 이름과 컨텍스트 경로(/admin)는 내장 서버가 있어야 적용된다.
 * 테스트용 application.yml이 main의 것을 가리므로 main과 같은 값을 여기서 준다(AdminSessionConfigTest가 main 값을 확인).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.context-path=/admin",
            "server.servlet.session.cookie.name=ADMIN_SESSION",
            "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN"
        })
class AdminLoginCookieTest {

    private static final String PASSWORD = "test-password-1!";
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String cookieHeader(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .map(value -> value.substring(0, value.indexOf(';') < 0 ? value.length() : value.indexOf(';')))
                .orElse(null);
    }

    @Test
    @DisplayName("FNC-C-001 관리자 앱 로그인은 ADMIN_SESSION 쿠키를 발급하고 /admin 아래 통계 메인으로 보낸다")
    void login_issuesAdminSessionCookie() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES ('cookie01', ?, '쿠키 관리자', 'ADMIN', 'ACTIVE', FALSE)",
                passwordEncoder.encode(PASSWORD));
        String base = "http://localhost:" + port + "/admin";

        HttpResponse<String> loginPage = send(
                HttpRequest.newBuilder(URI.create(base + "/login")).GET().build());
        assertThat(loginPage.statusCode()).isEqualTo(200);
        Matcher csrf = CSRF_FIELD.matcher(loginPage.body());
        assertThat(csrf.find()).as("로그인 화면의 CSRF 토큰").isTrue();
        String preLoginCookie = cookieHeader(loginPage, "ADMIN_SESSION");
        assertThat(preLoginCookie).as("로그인 화면이 발급한 세션 쿠키").isNotNull();

        String form = "username=cookie01&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", preLoginCookie)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());

        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElse("")).endsWith("/admin/stats");
        List<String> setCookies = login.headers().allValues("Set-Cookie");
        assertThat(setCookies).noneMatch(value -> value.startsWith("SESSION=") || value.startsWith("USER_SESSION="));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION_ADMIN WHERE PRINCIPAL_NAME = 'cookie01'", Integer.class))
                .isEqualTo(1);

        // 세션 고정 방어: 로그인 전에 받은 세션 ID는 로그인 뒤 쓰이지 않는다
        String loginCookie = cookieHeader(login, "ADMIN_SESSION");
        assertThat(loginCookie).as("로그인 뒤 새로 발급된 세션 쿠키").isNotNull().isNotEqualTo(preLoginCookie);
        String oldSessionId = new String(
                java.util.Base64.getDecoder().decode(preLoginCookie.substring("ADMIN_SESSION=".length())),
                StandardCharsets.UTF_8);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION_ADMIN WHERE SESSION_ID = ?", Integer.class, oldSessionId))
                .isZero();
        assertThat(login.headers().allValues("Set-Cookie"))
                .filteredOn(value -> value.startsWith("ADMIN_SESSION="))
                .allMatch(value -> value.contains("HttpOnly"));

        // 권한(ROLE_ADMIN)은 세션에 담기고 bcrypt 해시는 담기지 않는다
        String stored = new String(
                jdbcTemplate.queryForObject(
                        "SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ADMIN_ATTRIBUTES"
                                + " WHERE ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'",
                        byte[].class),
                StandardCharsets.ISO_8859_1);
        assertThat(stored).contains("ROLE_ADMIN").doesNotContain("$2a$10$");
    }
}
