package com.whiteeyes.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.admin.account.config.SuperAdminProperties;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.entity.AdminStatus;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class SuperAdminInitializerTest {

    private static final String LOGIN_ID = "test-super-admin";
    private static final String RAW_PASSWORD = "test-password-1!";
    private static final String SUPER_ADMIN_NAME = "최고 관리자";
    private static final int BCRYPT_LENGTH = 60;
    private static final String BCRYPT_STRENGTH_10_PREFIX = "$2a$10$";

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;

    SuperAdminInitializerTest(
            @Autowired AdminAccountRepository adminAccountRepository, @Autowired PasswordEncoder passwordEncoder) {
        this.adminAccountRepository = adminAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @BeforeEach
    void cleanAccounts() {
        // 앱이 켜질 때 이미 최고 관리자가 만들어졌으므로 비우고 시작한다
        adminAccountRepository.deleteAll();
    }

    private SuperAdminInitializer initializer(String loginId, String initialPassword) {
        return new SuperAdminInitializer(
                adminAccountRepository, passwordEncoder, new SuperAdminProperties(loginId, initialPassword));
    }

    @Test
    @DisplayName("FNC-A-001 최고 관리자가 없으면 환경변수 값으로 계정 1개를 만든다")
    void registerIfAbsent_noSuperAdmin_createsOne() {
        initializer(LOGIN_ID, RAW_PASSWORD).registerIfAbsent();

        assertThat(adminAccountRepository.findAll()).singleElement().satisfies(account -> {
            assertThat(account.getLoginId()).isEqualTo(LOGIN_ID);
            assertThat(account.getName()).isEqualTo(SUPER_ADMIN_NAME);
            assertThat(account.getRole()).isEqualTo(AdminRole.SUPER_ADMIN);
            assertThat(account.getStatus()).isEqualTo(AdminStatus.ACTIVE);
            assertThat(account.isMustChangePassword()).isTrue();
            assertThat(account.getCreatedBy()).isNull();
        });
    }

    @Test
    @DisplayName("FNC-A-001 초기 비밀번호는 평문이 아니라 bcrypt로 저장한다")
    void registerIfAbsent_savesPasswordAsBcrypt() {
        initializer(LOGIN_ID, RAW_PASSWORD).registerIfAbsent();

        String saved = adminAccountRepository.findAll().get(0).getPassword();
        assertThat(saved)
                .doesNotContain(RAW_PASSWORD)
                .hasSize(BCRYPT_LENGTH)
                .startsWith(BCRYPT_STRENGTH_10_PREFIX);
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved)).isTrue();
    }

    @Test
    @DisplayName("FNC-A-001 여러 번 실행해도 두 번 만들지 않는다")
    void registerIfAbsent_runTwice_stillOne() {
        SuperAdminInitializer initializer = initializer(LOGIN_ID, RAW_PASSWORD);

        initializer.registerIfAbsent();
        initializer.registerIfAbsent();

        assertThat(adminAccountRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-001 이미 최고 관리자가 있으면 환경변수가 달라도 아무것도 바꾸지 않는다")
    void registerIfAbsent_superAdminExists_changesNothing() {
        String existingPassword = passwordEncoder.encode("existing-password-1");
        adminAccountRepository.save(
                AdminAccount.createSuperAdmin("existing-super", existingPassword, SUPER_ADMIN_NAME));

        initializer(LOGIN_ID, RAW_PASSWORD).registerIfAbsent();

        assertThat(adminAccountRepository.findAll()).singleElement().satisfies(account -> {
            assertThat(account.getLoginId()).isEqualTo("existing-super");
            assertThat(account.getPassword()).isEqualTo(existingPassword);
        });
    }

    @Test
    @DisplayName("FNC-A-001 이미 최고 관리자가 있으면 환경변수가 비어 있어도 시작에 실패하지 않는다")
    void registerIfAbsent_superAdminExists_emptyEnvironmentIsOk() {
        adminAccountRepository.save(AdminAccount.createSuperAdmin("existing-super", "x".repeat(60), SUPER_ADMIN_NAME));

        assertThatCode(() -> initializer("", "").registerIfAbsent()).doesNotThrowAnyException();
        assertThat(adminAccountRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("FNC-A-001 아이디 환경변수가 비어 있으면 시작에 실패하고 아무것도 저장하지 않는다")
    void registerIfAbsent_blankLoginId_fails(String blank) {
        assertThatThrownBy(() -> initializer(blank, RAW_PASSWORD).registerIfAbsent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_LOGIN_ID")
                .hasMessageNotContaining(RAW_PASSWORD);
        assertThat(adminAccountRepository.count()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("FNC-A-001 비밀번호 환경변수가 비어 있으면 시작에 실패하고 아무것도 저장하지 않는다")
    void registerIfAbsent_blankPassword_fails(String blank) {
        assertThatThrownBy(() -> initializer(LOGIN_ID, blank).registerIfAbsent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_INITIAL_PASSWORD");
        assertThat(adminAccountRepository.count()).isZero();
    }

    @Test
    @DisplayName("tables.md admin_account 같은 아이디는 DB가 두 번 저장하지 못하게 막는다")
    void save_duplicateLoginId_rejectedByDatabase() {
        adminAccountRepository.saveAndFlush(AdminAccount.createSuperAdmin(LOGIN_ID, "x".repeat(60), SUPER_ADMIN_NAME));

        assertThatThrownBy(() -> adminAccountRepository.saveAndFlush(
                        AdminAccount.createSuperAdmin(LOGIN_ID, "y".repeat(60), SUPER_ADMIN_NAME)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
