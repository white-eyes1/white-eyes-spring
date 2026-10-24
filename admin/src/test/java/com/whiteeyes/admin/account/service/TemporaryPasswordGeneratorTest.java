package com.whiteeyes.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.common.constant.InputRules;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 임시 비밀번호 생성(conventions.md '임시 비밀번호', FNC-A-009 처리 순서 2) */
class TemporaryPasswordGeneratorTest {

    private static final int TRIES = 2_000;

    @Test
    @DisplayName("FNC-A-009 영문 대소문자·숫자로만 된 12자이고 대문자·소문자·숫자가 각각 1자 이상, 비밀번호 규칙도 통과한다")
    void composition() {
        TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
        for (int i = 0; i < TRIES; i++) {
            String password = generator.generate();

            assertThat(password).hasSize(12).matches("[A-Za-z0-9]+");
            assertThat(password).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");
            assertThat(password).matches(InputRules.PASSWORD_REGEX);
        }
    }

    @Test
    @DisplayName("FNC-A-009 부를 때마다 다른 값이 나온다")
    void differentEachTime() {
        TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < TRIES; i++) {
            passwords.add(generator.generate());
        }

        assertThat(passwords).hasSize(TRIES);
    }

    @Test
    @DisplayName("FNC-A-009 대문자·소문자·숫자가 늘 앞 세 자리에 고정되지 않게 섞는다")
    void shuffled() {
        TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator(new Random(1));
        Set<String> firstThreeKinds = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String head = generator.generate().substring(0, 3).replaceAll("[A-Z]", "U").replaceAll("[a-z]", "L")
                    .replaceAll("[0-9]", "D");
            firstThreeKinds.add(head);
        }

        assertThat(firstThreeKinds).hasSizeGreaterThan(1);
    }
}
