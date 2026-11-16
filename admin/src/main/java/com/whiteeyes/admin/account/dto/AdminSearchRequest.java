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
// 쿼리 문자열 값을 그대로 받는 레코드다. 값이 없으면 null로 오며, 기본값은 아래 compact 생성자가 채운다
public record AdminSearchRequest(
        String keyword,
        // 상태는 두 값 중 하나만 허용한다(비우면 null이라 검사하지 않음)
        @Pattern(regexp = STATUS_PATTERN, message = STATUS_MESSAGE) String status,
        Boolean active,
        // 정렬은 '기준' 또는 '기준,방향' 형식만 허용한다
        @Pattern(regexp = SORT_PATTERN, message = SORT_MESSAGE) String sort,
        // 페이지 번호는 0부터이며 음수는 거부한다
        @Min(value = 0, message = PAGE_MESSAGE) Integer page,
        // 한 페이지 개수는 1 이상, 상한은 사용자 목록과 같은 UserSearchRequest.MAX_SIZE를 쓴다
        @Min(value = 1, message = SIZE_MESSAGE) @Max(value = UserSearchRequest.MAX_SIZE, message = SIZE_MESSAGE)
                Integer size) {

    // 검증용 정규식과 오류 문구. 정규식과 문구가 서로 맞아야 하므로 한곳에 모아 둔다
    private static final String STATUS_PATTERN = "ACTIVE|DISABLED";
    private static final String SORT_PATTERN = "(createdAt|loginId|lastActiveAt)(,(asc|desc))?";
    private static final String STATUS_MESSAGE = "상태는 ACTIVE·DISABLED 중 하나여야 합니다.";
    private static final String SORT_MESSAGE = "정렬은 createdAt·loginId·lastActiveAt 중 하나와 asc·desc로 지정해 주세요.";
    private static final String PAGE_MESSAGE = "페이지는 0 이상이어야 합니다.";
    private static final String SIZE_MESSAGE = "한 페이지 개수는 1~100이어야 합니다.";
    // 정렬 방향 문자열. 방향이 없거나 desc가 아니면 오름차순으로 본다(isAscending)
    private static final String DESC = "desc";

    /** 생성할 때 비었거나 공백뿐인 값을 null로 정리하고, 빠진 값에 기본값을 채운다. */
    public AdminSearchRequest {
        // 검색어: 비었으면 null(조건 없음), 아니면 앞뒤 공백을 지운다
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        // 상태: 빈 문자열도 '전체'로 취급해 null로 맞춘다
        status = status == null || status.isBlank() ? null : status;
        // 정렬: 없으면 사용자 목록과 같은 기본값(최근 가입 순)
        sort = sort == null || sort.isBlank() ? UserSearchRequest.DEFAULT_SORT : sort;
        // 페이지: 없으면 첫 페이지
        page = page == null ? 0 : page;
        // 크기: 없으면 사용자 목록과 같은 기본 크기
        size = size == null ? UserSearchRequest.DEFAULT_SIZE : size;
    }

    /** 검증을 통과한 뒤에만 부른다. */
    public AccountSortField sortField() {
        // 쉼표 앞부분이 정렬 기준이다("loginId,desc" -> "loginId")
        return AccountSortField.fromParamName(sort.split(",")[0]);
    }

    /** 오름차순인지. 방향을 생략했거나 desc가 아니면 오름차순이다. */
    public boolean isAscending() {
        String[] parts = sort.split(",");
        // 쉼표 뒤 값이 없거나 desc가 아닐 때만 true
        return parts.length < 2 || !DESC.equals(parts[1]);
    }

    /** 세션 정보를 붙여야 거르거나 정렬할 수 있는 조건인지(사용자 목록과 같은 기준). */
    public boolean isSessionDependent() {
        return active != null || sortField() == AccountSortField.LAST_ACTIVE_AT;
    }
}
