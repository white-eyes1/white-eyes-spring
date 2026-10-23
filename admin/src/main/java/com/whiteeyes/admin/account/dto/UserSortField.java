package com.whiteeyes.admin.account.dto;

import java.util.Arrays;

/** 사용자 목록 정렬 기준(FNC-A-002 입력 항목 sort). */
public enum UserSortField {
    CREATED_AT("createdAt"),
    LOGIN_ID("loginId"),
    // 세션 정보를 붙인 뒤에야 정렬할 수 있어 메모리에서 처리한다(FNC-A-002 업무 규칙)
    LAST_ACTIVE_AT("lastActiveAt");

    private final String paramName;

    UserSortField(String paramName) {
        this.paramName = paramName;
    }

    public String getParamName() {
        return paramName;
    }

    static UserSortField fromParamName(String paramName) {
        return Arrays.stream(values())
                .filter(field -> field.paramName.equals(paramName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 정렬 기준: " + paramName));
    }
}
