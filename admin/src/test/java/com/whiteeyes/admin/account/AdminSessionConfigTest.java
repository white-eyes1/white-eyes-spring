package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * 두 앱에 동시에 로그인해도 세션이 덮어써지지 않으려면 쿠키 이름과 세션 테이블이 앱마다 달라야 한다(FNC-C-001).
 * 테스트용 application.yml이 main의 것을 가려서 main 파일을 직접 읽는다.
 */
class AdminSessionConfigTest {

    @Test
    @DisplayName("FNC-C-001 관리자 앱은 ADMIN_SESSION 쿠키와 SPRING_SESSION_ADMIN 테이블을 쓴다")
    void adminApp_usesOwnCookieAndSessionTable() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties.getProperty("server.servlet.session.cookie.name")).isEqualTo("ADMIN_SESSION");
        assertThat(properties.getProperty("spring.session.jdbc.table-name")).isEqualTo("SPRING_SESSION_ADMIN");
    }
}
