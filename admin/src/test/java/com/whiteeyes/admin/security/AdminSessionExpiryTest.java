package com.whiteeyes.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 앱 세션 만료·정리(FNC-C-004). 검수 기준: 30분 무동작 후 요청 시 로그인 화면으로 이동, 만료 세션 행이 정리됨.
 * 만료 판정은 저장된 마지막 접근 시각(LAST_ACCESS_TIME)과 허용 시간으로 계산되므로(트러블슈팅 0016) 마지막 접근 시각을 과거로 바꿔 시험한다.
 */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminSessionExpiryTest {

    private static final String PASSWORD = "test-password-1!";
    private static final String LOGIN_ID = "expiry-admin";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String LOGIN_REDIRECT = "/admin/login";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    @DisplayName("FNC-C-004 마지막 요청 후 30분이 지난 세션으로 요청하면 로그인 화면으로 이동")
    void requestAfter30Minutes_redirectsToLogin() throws Exception {
        Cookie session = login();
        idleFor(Duration.ofMinutes(31));

        mockMvc.perform(get("/admin/stats").contextPath("/admin").cookie(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));
    }

    @Test
    @DisplayName("FNC-C-004 30분이 되기 전 세션은 그대로 쓸 수 있다")
    void requestBefore30Minutes_stillLoggedIn() throws Exception {
        Cookie session = login();
        idleFor(Duration.ofMinutes(29));

        mockMvc.perform(get("/admin/stats").contextPath("/admin").cookie(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-C-004 정리 작업은 만료된 세션 행만 지운다")
    void cleanUp_deletesOnlyExpiredSessions() throws Exception {
        login();
        idleFor(Duration.ofMinutes(31));
        jdbcTemplate.update("UPDATE SPRING_SESSION_ADMIN SET EXPIRY_TIME = 1");
        login();
        assertThat(sessionRows()).isEqualTo(2);

        sessionRepository.cleanUpExpiredSessions();

        assertThat(sessionRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN_ATTRIBUTES", Long.class))
                .as("지워진 세션의 속성 행도 함께 사라진다")
                .isEqualTo(attributeRowsOfLiveSessions());
    }

    @Test
    @DisplayName("FNC-C-004 정리 작업은 10분마다, 세션 허용 시간은 SESSION_TIMEOUT(기본 30m)")
    void mainConfig_cleanupEvery10Minutes_timeoutFromEnv() {
        Properties properties = mainApplicationYaml();

        assertThat(properties.getProperty("spring.session.jdbc.cleanup-cron")).isEqualTo("0 */10 * * * *");
        String timeout = properties.getProperty("server.servlet.session.timeout");
        assertThat(resolve(timeout, Map.of())).isEqualTo("30m");
        assertThat(resolve(timeout, Map.of("SESSION_TIMEOUT", "10m"))).isEqualTo("10m");
    }

    // 로그인 화면으로 실제 로그인해 받은 세션 쿠키. 부를 때마다 새 세션이 생긴다
    private Cookie login() throws Exception {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_account WHERE login_id = ?", Long.class, LOGIN_ID)
                == 0) {
            jdbcTemplate.update(
                    "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                            + " VALUES (?, ?, '세션 만료 테스트', 'ADMIN', 'ACTIVE', 0)",
                    LOGIN_ID,
                    passwordEncoder.encode(PASSWORD));
        }
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

    // 지금 있는 세션 모두의 마지막 접근 시각을 idle 만큼 과거로 돌린다
    private void idleFor(Duration idle) {
        jdbcTemplate.update(
                "UPDATE SPRING_SESSION_ADMIN SET LAST_ACCESS_TIME = ?", System.currentTimeMillis() - idle.toMillis());
    }

    private long sessionRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ADMIN", Long.class);
    }

    private long attributeRowsOfLiveSessions() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION_ADMIN_ATTRIBUTES a JOIN SPRING_SESSION_ADMIN s"
                        + " ON a.SESSION_PRIMARY_ID = s.PRIMARY_ID",
                Long.class);
    }

    // 테스트용 application.yml이 main의 것을 가려서 main 파일을 직접 읽는다
    private Properties mainApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        return yaml.getObject();
    }

    private String resolve(String placeholderValue, Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", environmentVariables));
        return environment.resolvePlaceholders(placeholderValue);
    }
}
