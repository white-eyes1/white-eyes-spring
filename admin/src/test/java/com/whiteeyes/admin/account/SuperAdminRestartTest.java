package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.admin.AdminApplication;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 관리자 앱을 실제로 두 번 띄워서 재시작을 흉내 낸다. 테스트마다 DB 이름을 따로 써서 다른 테스트와 섞이지 않는다.
 */
class SuperAdminRestartTest {

    private static final String LOGIN_ID = "restart-super-admin";
    private static final String RAW_PASSWORD = "test-password-1!";

    private ConfigurableApplicationContext startAdminApp(String dbName, String loginId, String initialPassword) {
        return new SpringApplicationBuilder(AdminApplication.class)
                .run(
                        "--spring.main.web-application-type=none",
                        "--spring.datasource.url=jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "--super-admin.login-id=" + loginId,
                        "--super-admin.initial-password=" + initialPassword);
    }

    @Test
    @DisplayName("FNC-A-001 앱을 다시 켜도 최고 관리자를 두 번 만들지 않는다")
    void restart_doesNotCreateSecondSuperAdmin() {
        String dbName = "restart-twice";
        Long firstId;
        String firstPassword;

        try (ConfigurableApplicationContext firstRun = startAdminApp(dbName, LOGIN_ID, RAW_PASSWORD)) {
            AdminAccountRepository repository = firstRun.getBean(AdminAccountRepository.class);
            assertThat(repository.count()).isEqualTo(1);
            AdminAccount created = repository.findAll().get(0);
            firstId = created.getId();
            firstPassword = created.getPassword();
        }

        try (ConfigurableApplicationContext secondRun = startAdminApp(dbName, LOGIN_ID, RAW_PASSWORD)) {
            AdminAccountRepository repository = secondRun.getBean(AdminAccountRepository.class);
            assertThat(repository.findAll()).singleElement().satisfies(account -> {
                assertThat(account.getId()).isEqualTo(firstId);
                assertThat(account.getPassword()).isEqualTo(firstPassword);
            });
        }
    }

    @Test
    @DisplayName("FNC-A-001 최고 관리자가 없는데 환경변수도 없으면 앱이 시작되지 않고 이유가 남는다")
    void start_withoutEnvironment_fails() {
        assertThatThrownBy(() -> startAdminApp("restart-fail", "", ""))
                .hasStackTraceContaining("SUPER_ADMIN_LOGIN_ID");
    }
}
