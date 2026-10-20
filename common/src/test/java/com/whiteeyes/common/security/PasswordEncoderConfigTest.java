package com.whiteeyes.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(PasswordEncoderConfig.class)
class PasswordEncoderConfigTest {

    private static final String RAW_PASSWORD = "test-password-1!";
    private static final int BCRYPT_LENGTH = 60;
    private static final String BCRYPT_STRENGTH_10_PREFIX = "$2a$10$";

    private final PasswordEncoder passwordEncoder;

    PasswordEncoderConfigTest(@Autowired PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    @DisplayName("FNC-C-005 같은 비밀번호도 저장값이 매번 다르다")
    void encode_samePassword_differentResult() {
        String first = passwordEncoder.encode(RAW_PASSWORD);
        String second = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("FNC-C-005 저장값에 평문이 없고 강도 10의 60자 bcrypt 문자열이다")
    void encode_result_isBcryptStrength10() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(encoded)
                .doesNotContain(RAW_PASSWORD)
                .hasSize(BCRYPT_LENGTH)
                .startsWith(BCRYPT_STRENGTH_10_PREFIX);
    }

    @Test
    @DisplayName("FNC-C-005 matches는 맞는 비밀번호에 true를 돌려준다")
    void matches_rightPassword_true() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(passwordEncoder.matches(RAW_PASSWORD, encoded)).isTrue();
    }

    @Test
    @DisplayName("FNC-C-005 matches는 틀린 비밀번호에 false를 돌려준다")
    void matches_wrongPassword_false() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(passwordEncoder.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    @DisplayName("FNC-C-005 같은 비밀번호로 만든 서로 다른 저장값이 모두 matches를 통과한다")
    void matches_differentHashesOfSamePassword_allTrue() {
        String first = passwordEncoder.encode(RAW_PASSWORD);
        String second = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(passwordEncoder.matches(RAW_PASSWORD, first)).isTrue();
        assertThat(passwordEncoder.matches(RAW_PASSWORD, second)).isTrue();
    }
}
