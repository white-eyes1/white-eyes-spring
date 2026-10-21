package com.whiteeyes.admin.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * 접근 권한 검사(FNC-C-003)가 켜진 뒤에도 기능 테스트가 로그인한 관리자로 요청하도록, 모든 MockMvc 요청에
 * ADMIN 사용자와 CSRF 토큰을 붙인다. 권한 자체를 보는 테스트(AccessControlTest 등)는 쓰지 않는다.
 */
@TestConfiguration
public class AdminTestUserConfig {

    @Bean
    public MockMvcBuilderCustomizer adminUserMockMvcCustomizer() {
        return builder -> builder.defaultRequest(get("/").with(user("admin01").roles("ADMIN")).with(csrf()));
    }
}
