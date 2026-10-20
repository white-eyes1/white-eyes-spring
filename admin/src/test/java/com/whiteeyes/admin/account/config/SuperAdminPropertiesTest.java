package com.whiteeyes.admin.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class SuperAdminPropertiesTest {

    // 테스트 자원의 application.yml이 main 것을 가려서, main 파일을 경로로 직접 읽는다
    private static final String MAIN_APPLICATION_YML = "file:src/main/resources/application.yml";

    @Test
    @DisplayName("FNC-A-001 main application.yml이 환경변수 이름 두 개를 설정에 연결한다")
    void mainApplicationYml_mapsEnvironmentVariableNames() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.config.location=" + MAIN_APPLICATION_YML,
                        "SUPER_ADMIN_LOGIN_ID=env-super-admin",
                        "SUPER_ADMIN_INITIAL_PASSWORD=env-password-1")
                .run()) {
            SuperAdminProperties properties = Binder.get(context.getEnvironment())
                    .bind("super-admin", SuperAdminProperties.class)
                    .get();

            assertThat(properties.loginId()).isEqualTo("env-super-admin");
            assertThat(properties.initialPassword()).isEqualTo("env-password-1");
        }
    }

    @Test
    @DisplayName("toString은 초기 비밀번호를 찍지 않는다")
    void toString_masksInitialPassword() {
        SuperAdminProperties properties = new SuperAdminProperties("env-super-admin", "env-password-1");

        assertThat(properties.toString())
                .contains("env-super-admin")
                .doesNotContain("env-password-1");
    }

    @Configuration
    static class EmptyConfig {}
}
