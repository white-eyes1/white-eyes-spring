package com.whiteeyes.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 암호화(FNC-C-005). 사용자 앱과 관리자 앱이 이 빈 하나를 주입받아 저장 시 encode,
 * 로그인·현재 비밀번호 확인 시 matches를 쓴다. 앱별 보안 설정에서 PasswordEncoder 빈을 다시 만들지 않는다.
 */
@Configuration
public class PasswordEncoderConfig {

    // 강도 10(FNC-C-005)
    private static final int BCRYPT_STRENGTH = 10;

    /**
     * 강도 10의 BCryptPasswordEncoder.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
