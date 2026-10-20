package com.whiteeyes.admin.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최고 관리자 최초 등록용 값(FNC-A-001). application.yml이 환경변수 SUPER_ADMIN_LOGIN_ID,
 * SUPER_ADMIN_INITIAL_PASSWORD를 여기에 연결한다.
 */
@ConfigurationProperties(prefix = "super-admin")
public record SuperAdminProperties(String loginId, String initialPassword) {}
