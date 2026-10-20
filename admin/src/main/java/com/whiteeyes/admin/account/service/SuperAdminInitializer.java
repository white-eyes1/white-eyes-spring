package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.config.SuperAdminProperties;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 관리자 앱이 켜질 때 최고 관리자가 없으면 환경변수 값으로 한 번 만든다(FNC-A-001).
 */
@Component
@EnableConfigurationProperties(SuperAdminProperties.class)
public class SuperAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminInitializer.class);

    private static final String LOGIN_ID_VARIABLE = "SUPER_ADMIN_LOGIN_ID";
    private static final String INITIAL_PASSWORD_VARIABLE = "SUPER_ADMIN_INITIAL_PASSWORD";
    // 명세에 최고 관리자의 이름이 없는데 name 칸이 NOT NULL이라 고정 문구를 쓴다
    private static final String SUPER_ADMIN_NAME = "최고 관리자";

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminProperties properties;

    public SuperAdminInitializer(
            AdminAccountRepository adminAccountRepository,
            PasswordEncoder passwordEncoder,
            SuperAdminProperties properties) {
        this.adminAccountRepository = adminAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        registerIfAbsent();
    }

    /**
     * 최고 관리자가 이미 있으면 아무것도 하지 않는다(환경변수도 보지 않는다). 없을 때만 환경변수 값이 필요하고,
     * 비어 있으면 예외를 던져 앱이 시작되지 않게 한다. 비밀번호는 로그와 예외 메시지에 쓰지 않는다.
     */
    public void registerIfAbsent() {
        if (adminAccountRepository.existsByRole(AdminRole.SUPER_ADMIN)) {
            log.debug("최고 관리자가 이미 있어 등록하지 않습니다");
            return;
        }

        String loginId = requireValue(properties.loginId(), LOGIN_ID_VARIABLE);
        String initialPassword = requireValue(properties.initialPassword(), INITIAL_PASSWORD_VARIABLE);

        String encodedPassword = passwordEncoder.encode(initialPassword);
        adminAccountRepository.save(AdminAccount.createSuperAdmin(loginId, encodedPassword, SUPER_ADMIN_NAME));
        log.info("최고 관리자를 등록했습니다 loginId={}", loginId);
    }

    private String requireValue(String value, String variableName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("환경변수 " + variableName + " 값이 비어 있어 최고 관리자를 만들 수 없습니다");
        }
        return value;
    }
}
