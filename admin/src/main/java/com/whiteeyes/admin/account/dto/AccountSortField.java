package com.whiteeyes.admin.account.dto;

import java.util.Arrays;

/** 사용자·관리자 목록 정렬 기준(FNC-A-002·003 입력 항목 sort). */
public enum AccountSortField {
    // 괄호 안 문자열이 요청의 sort 파라미터에 쓰는 이름이다(대소문자 구분)
    CREATED_AT("createdAt"),
    LOGIN_ID("loginId"),
    // 세션 정보를 붙인 뒤에야 정렬할 수 있어 메모리에서 처리한다(FNC-A-002 업무 규칙)
    LAST_ACTIVE_AT("lastActiveAt");

    // sort 파라미터의 쉼표 앞부분과 비교하는 이름
    private final String paramName;

    AccountSortField(String paramName) {
        this.paramName = paramName;
    }

    /** 이 정렬 기준의 파라미터 이름. */
    public String getParamName() {
        return paramName;
    }

    /**
     * sort 파라미터의 앞부분 문자열로 정렬 기준을 찾는다. 요청 검증(정규식)을 통과한 값만 오므로
     * 못 찾는 경우는 입력 오류가 아니라 프로그램 오류로 보고 예외를 던진다.
     */
    static AccountSortField fromParamName(String paramName) {
        // 모든 상수 중 이름이 같은 것 하나를 찾는다
        return Arrays.stream(values())
                .filter(field -> field.paramName.equals(paramName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 정렬 기준: " + paramName));
    }
}
