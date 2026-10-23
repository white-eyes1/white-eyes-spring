package com.whiteeyes.admin.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 관리자 목록 조회 조건(FNC-A-003 입력 항목, GET /api/v1/admins). 비운 값은 그 조건으로 거르지 않는다.
 * 사용자 목록(UserSearchRequest)과 같은 규칙이고, 명세 입력 항목에 가입일이 없어 가입일 조건은 없다.
 *
 * @param keyword 아이디·이름 검색어
 * @param status ACTIVE·DISABLED. 비우면 전체
 * @param active 활동 중 여부
 * @param sort {createdAt|loginId|lastActiveAt},{asc|desc}. 방향을 빼면 asc
 * @param page 0부터
 * @param size 1~100
 */
public record AdminSearchRequest(
        String keyword,
        @Pattern(regexp = STATUS_PATTERN, message = STATUS_MESSAGE) String status,
        Boolean active,
        @Pattern(regexp = SORT_PATTERN, message = SORT_MESSAGE) String sort,
        @Min(value = 0, message = PAGE_MESSAGE) Integer page,
        @Min(value = 1, message = SIZE_MESSAGE) @Max(value = UserSearchRequest.MAX_SIZE, message = SIZE_MESSAGE)
                Integer size) {

    private static final String STATUS_PATTERN = "ACTIVE|DISABLED";
    private static final String SORT_PATTERN = "(createdAt|loginId|lastActiveAt)(,(asc|desc))?";
    private static final String STATUS_MESSAGE = "상태는 ACTIVE·DISABLED 중 하나여야 합니다.";
    private static final String SORT_MESSAGE = "정렬은 createdAt·loginId·lastActiveAt 중 하나와 asc·desc로 지정해 주세요.";
    private static final String PAGE_MESSAGE = "페이지는 0 이상이어야 합니다.";
    private static final String SIZE_MESSAGE = "한 페이지 개수는 1~100이어야 합니다.";
    private static final String DESC = "desc";

    public AdminSearchRequest {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        status = status == null || status.isBlank() ? null : status;
        sort = sort == null || sort.isBlank() ? UserSearchRequest.DEFAULT_SORT : sort;
        page = page == null ? 0 : page;
        size = size == null ? UserSearchRequest.DEFAULT_SIZE : size;
    }

    /** 검증을 통과한 뒤에만 부른다. */
    public AccountSortField sortField() {
        return AccountSortField.fromParamName(sort.split(",")[0]);
    }

    public boolean isAscending() {
        String[] parts = sort.split(",");
        return parts.length < 2 || !DESC.equals(parts[1]);
    }

    /** 세션 정보를 붙여야 거르거나 정렬할 수 있는 조건인지(사용자 목록과 같은 기준). */
    public boolean isSessionDependent() {
        return active != null || sortField() == AccountSortField.LAST_ACTIVE_AT;
    }
}
