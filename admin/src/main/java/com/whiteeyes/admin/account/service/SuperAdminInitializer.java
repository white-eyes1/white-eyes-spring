package com.whiteeyes.admin.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.account.config.SuperAdminProperties;
import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;

/**
 * 관리자 앱이 켜질 때 최고 관리자가 없으면 환경변수 값으로 한 번 만든다(FNC-A-001).
 */
@Slf4j
@Component
@EnableConfigurationProperties(SuperAdminProperties.class)
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private static final String LOGIN_ID_VARIABLE = "SUPER_ADMIN_LOGIN_ID";
    private static final String INITIAL_PASSWORD_VARIABLE = "SUPER_ADMIN_INITIAL_PASSWORD";
    // 명세에 최고 관리자의 이름이 없는데 name 칸이 NOT NULL이라 고정 문구를 쓴다
    private static final String SUPER_ADMIN_NAME = "최고 관리자";

    // 최고 관리자 존재 확인·저장
    private final AdminAccountRepository adminAccountRepository;
    // 초기 비밀번호를 bcrypt로 암호화한다
    private final PasswordEncoder passwordEncoder;
    // 환경변수에서 읽어 온 아이디·초기 비밀번호
    private final SuperAdminProperties properties;

    /** 앱이 시작될 때 스프링 부트가 한 번 부른다. 실제 일은 registerIfAbsent가 한다. */
    @Override
    /** 앱이 시작될 때 스프링 부트가 한 번 부른다. 실제 일은 registerIfAbsent가 한다. */
    public void run(ApplicationArguments args) {
        registerIfAbsent();
    }

    /**
     * 최고 관리자가 이미 있으면 아무것도 하지 않는다(환경변수도 보지 않는다). 없을 때만 환경변수 값이 필요하고,
     * 비어 있으면 예외를 던져 앱이 시작되지 않게 한다. 비밀번호는 로그와 예외 메시지에 쓰지 않는다.
     */
    public void registerIfAbsent() {
        // 환경변수 검사보다 먼저 확인한다. 이미 있으면 환경변수가 비어 있어도 앱 시작에 영향이 없다
        if (adminAccountRepository.existsByRole(AdminRole.SUPER_ADMIN)) {
            log.debug("최고 관리자가 이미 있어 등록하지 않습니다");
            return;
        }

        // 최고 관리자가 없을 때만 필요한 값이라 이 시점에 검사한다
        String loginId = requireValue(properties.loginId(), LOGIN_ID_VARIABLE);
        String initialPassword = requireValue(properties.initialPassword(), INITIAL_PASSWORD_VARIABLE);

        // 평문 초기 비밀번호는 암호화한 값만 저장한다
        String encodedPassword = passwordEncoder.encode(initialPassword);
        adminAccountRepository.save(AdminAccount.createSuperAdmin(loginId, encodedPassword, SUPER_ADMIN_NAME));
        // 로그에는 아이디만 남기고 비밀번호는 남기지 않는다
        log.info("최고 관리자를 등록했습니다 loginId={}", loginId);
    }

    /** 값이 비었으면 예외로 앱 시작을 막는다(최고 관리자 없이 운영되는 것을 방지). 메시지에는 변수 이름만 쓰고 값은 쓰지 않는다. */
    private String requireValue(String value, String variableName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("환경변수 " + variableName + " 값이 비어 있어 최고 관리자를 만들 수 없습니다");
        }
        return value;
    }
}
