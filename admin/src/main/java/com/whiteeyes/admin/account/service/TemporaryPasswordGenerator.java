package com.whiteeyes.admin.account.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 임시 비밀번호를 만든다(conventions.md '임시 비밀번호': 영문 대소문자·숫자 12자 무작위).
 * 비밀번호 규칙(영문·숫자 각 1자 이상)에 걸리지 않도록 대문자·소문자·숫자를 각각 1자 이상 넣는다(#34에서 정함).
 * 관리자 계정 생성(FNC-A-009)이 쓴다. 비밀번호 재설정(FNC-A-005·010)도 같은 규칙이다.
 */
@Component
public class TemporaryPasswordGenerator {

    static final int LENGTH = 12;
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String ALL = UPPER + LOWER + DIGITS;

    private final RandomGenerator random;

    // 생성자가 둘이라 빈을 만들 때 쓸 쪽을 정한다
    @Autowired
    public TemporaryPasswordGenerator() {
        this(new SecureRandom());
    }

    // 테스트에서 값을 고정하려고 난수 생성기를 받는다
    TemporaryPasswordGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGITS));
        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }
        // 앞 세 자리가 늘 대문자·소문자·숫자 순이 되지 않게 같은 난수 생성기로 섞는다(Fisher–Yates)
        for (int i = chars.size() - 1; i > 0; i--) {
            Collections.swap(chars, i, random.nextInt(i + 1));
        }
        StringBuilder password = new StringBuilder(LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    private char pick(String pool) {
        return pool.charAt(random.nextInt(pool.length()));
    }
}
