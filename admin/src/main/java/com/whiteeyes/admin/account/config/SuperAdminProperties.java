package com.whiteeyes.admin.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최고 관리자 최초 등록용 값(FNC-A-001). application.yml이 환경변수 SUPER_ADMIN_LOGIN_ID,
 * SUPER_ADMIN_INITIAL_PASSWORD를 여기에 연결한다.
 *
 * 값이 비어 있는지는 이 클래스가 아니라 SuperAdminInitializer가 등록 시점에 검사한다.
 * 초기 비밀번호는 평문으로 들어오므로 아래 toString에서 가려 로그에 새지 않게 한다.
 */
@ConfigurationProperties(prefix = "super-admin")
public record SuperAdminProperties(String loginId, String initialPassword) {

    // 기본 toString이 초기 비밀번호를 로그에 남기지 않게 가린다
    @Override
    public String toString() {
        return "SuperAdminProperties[loginId=" + loginId + ", initialPassword=****]";
    }
}
