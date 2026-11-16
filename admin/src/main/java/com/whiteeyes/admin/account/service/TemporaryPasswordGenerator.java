package com.whiteeyes.admin.account.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

/**
 * 임시 비밀번호를 만든다(conventions.md '임시 비밀번호': 영문 대소문자·숫자 12자 무작위).
 * 비밀번호 규칙(영문·숫자 각 1자 이상)에 걸리지 않도록 대문자·소문자·숫자를 각각 1자 이상 넣는다(#34에서 정함).
 * 관리자 계정 생성(FNC-A-009)이 쓴다. 비밀번호 재설정(FNC-A-005·010)도 같은 규칙이다.
 */
@Component
public class TemporaryPasswordGenerator {

    // 임시 비밀번호 길이
    static final int LENGTH = 12;
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    // 나머지 자리를 채울 때 고르는 전체 글자 집합
    private static final String ALL = UPPER + LOWER + DIGITS;

    // 예측하기 어려워야 하므로 일반 난수가 아니라 암호학적으로 안전한 SecureRandom을 쓴다
    private final RandomGenerator random = new SecureRandom();

    /** 대문자·소문자·숫자를 각각 1자 이상 포함한 12자 임시 비밀번호(평문)를 만든다. */
    public String generate() {
        // 글자를 모아 두는 목록. 나중에 위치를 섞어야 해서 문자열 대신 리스트를 쓴다
        List<Character> chars = new ArrayList<>(LENGTH);
        // 비밀번호 규칙(영문·숫자 각 1자 이상)을 항상 만족하도록 세 종류를 먼저 하나씩 넣는다
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGITS));
        // 나머지 자리는 전체 글자 집합에서 무작위로 채운다
        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }
        // 앞 세 자리가 늘 대문자·소문자·숫자 순이 되지 않게 같은 난수 생성기로 섞는다(Fisher–Yates)
        for (int i = chars.size() - 1; i > 0; i--) {
            Collections.swap(chars, i, random.nextInt(i + 1));
        }
        // 섞은 글자 목록을 문자열 하나로 합친다
        StringBuilder password = new StringBuilder(LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    // 주어진 글자 집합에서 무작위로 한 글자를 고른다
    private char pick(String pool) {
        return pool.charAt(random.nextInt(pool.length()));
    }
}
