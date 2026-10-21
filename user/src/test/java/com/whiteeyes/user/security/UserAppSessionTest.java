package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 사용자 앱의 로그인은 아직 없다. 로그인용 부품이 들어와도 앱이 잠기지 않고 세션 쿠키 이름만 나뉘어 있는지 본다(FNC-C-001).
 */
@SpringBootTest
class UserAppSessionTest {

    @Autowired
    private ApplicationContext context;

    // 테스트용 application.yml이 main의 것을 가리므로 main 파일을 직접 읽는다
    private Properties mainApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        return yaml.getObject();
    }

    @Test
    @DisplayName("FNC-C-001 사용자 앱은 USER_SESSION 쿠키와 SPRING_SESSION_USER 테이블을 쓴다")
    void userApp_usesOwnCookieAndSessionTable() {
        Properties properties = mainApplicationYaml();

        assertThat(properties.getProperty("server.servlet.session.cookie.name")).isEqualTo("USER_SESSION");
        assertThat(properties.getProperty("spring.session.jdbc.table-name")).isEqualTo("SPRING_SESSION_USER");
    }

    @Test
    @DisplayName("FNC-C-001 사용자 앱은 아직 보안 필터 체인이 없어 다른 기능이 잠기지 않는다")
    void userApp_hasNoSecurityFilterChainYet() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
    }
}
