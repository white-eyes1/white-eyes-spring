package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.common.security.AccountPrincipal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * 사용자 앱 세션 만료·정리(FNC-C-004). 사용자 앱 로그인이 아직 없어(이슈 #126) 세션을 저장소에 직접 만든다.
 * "만료 뒤 요청하면 로그인 화면으로 이동"은 사용자 앱 권한 표(#126)가 있어야 확인할 수 있어, 여기서는
 * 30분이 지난 세션을 저장소가 더는 돌려주지 않는지와 정리 작업까지 본다.
 * 사용자 앱은 Flyway를 돌리지 않아(관리자 앱만 실행) 테스트 H2의 세션 표는 Spring Session이 기본 이름(SPRING_SESSION)으로 만든다.
 */
@SpringBootTest
class UserSessionExpiryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
    }

    @Test
    @DisplayName("FNC-C-004 마지막 접근 후 30분이 지난 세션은 저장소가 돌려주지 않고, 30분 전이면 돌려준다")
    void sessionIdleOver30Minutes_isNotFound() {
        String idle31 = createSession();
        String idle29 = createSession();
        idleFor(idle31, Duration.ofMinutes(31));
        idleFor(idle29, Duration.ofMinutes(29));

        assertThat(sessionRepository.findById(idle31)).isNull();
        assertThat(sessionRepository.findById(idle29)).isNotNull();
    }

    @Test
    @DisplayName("FNC-C-004 정리 작업은 만료된 세션 행만 지운다")
    void cleanUp_deletesOnlyExpiredSessions() {
        String expired = createSession();
        createSession();
        idleFor(expired, Duration.ofMinutes(31));
        jdbcTemplate.update("UPDATE SPRING_SESSION SET EXPIRY_TIME = 1 WHERE SESSION_ID = ?", expired);
        assertThat(sessionRows()).isEqualTo(2);

        sessionRepository.cleanUpExpiredSessions();

        assertThat(sessionRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES", Long.class))
                .as("지워진 세션의 속성 행도 함께 사라진다")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("FNC-C-004 정리 작업은 10분마다, 세션 허용 시간은 SESSION_TIMEOUT(기본 30m)")
    void mainConfig_cleanupEvery10Minutes_timeoutFromEnv() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        // 테스트용 application.yml이 main의 것을 가려서 main 파일을 직접 읽는다
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties.getProperty("spring.session.jdbc.cleanup-cron")).isEqualTo("0 */10 * * * *");
        String timeout = properties.getProperty("server.servlet.session.timeout");
        assertThat(resolve(timeout, Map.of())).isEqualTo("30m");
        assertThat(resolve(timeout, Map.of("SESSION_TIMEOUT", "10m"))).isEqualTo("10m");
    }

    // 로그인한 것처럼 속성이 있는 세션을 저장소에 직접 만들고 세션 번호를 돌려준다
    @SuppressWarnings("unchecked")
    private String createSession() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new AccountPrincipal(1L, "someone", "encoded", true, false, authorities);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        Session session = sessionRepository.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        // JdbcSession 클래스가 비공개라 저장은 인터페이스(SessionRepository)로 한다
        ((SessionRepository<Session>) (SessionRepository<?>) sessionRepository).save(session);
        return session.getId();
    }

    private void idleFor(String sessionId, Duration idle) {
        jdbcTemplate.update(
                "UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = ? WHERE SESSION_ID = ?",
                System.currentTimeMillis() - idle.toMillis(),
                sessionId);
    }

    private long sessionRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);
    }

    private String resolve(String placeholderValue, Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", environmentVariables));
        return environment.resolvePlaceholders(placeholderValue);
    }
}
