package com.whiteeyes.admin.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 사용자 목록 조회 조건(FNC-A-002 입력 항목, GET /api/v1/users). 비운 값은 그 조건으로 거르지 않는다.
 *
 * @param keyword 아이디·이름 검색어
 * @param status PENDING·ACTIVE·WITHDRAWN. 비우면 탈퇴 계정까지 전체(#32에서 정함)
 * @param joinedFrom 가입일 시작(그날 포함)
 * @param joinedTo 가입일 끝(그날 포함, #32에서 정함)
 * @param active 활동 중 여부
 * @param sort {createdAt|loginId|lastActiveAt},{asc|desc}. 방향을 빼면 asc
 * @param page 0부터
 * @param size 1~100
 */
@JoinedDateRange
public record UserSearchRequest(
        String keyword,
        @Pattern(regexp = STATUS_PATTERN, message = STATUS_MESSAGE) String status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
        Boolean active,
        @Pattern(regexp = SORT_PATTERN, message = SORT_MESSAGE) String sort,
        @Min(value = 0, message = PAGE_MESSAGE) Integer page,
        @Min(value = 1, message = SIZE_MESSAGE) @Max(value = MAX_SIZE, message = SIZE_MESSAGE) Integer size) {

    // 페이지 기본값·최대값(conventions.md '공통 API 규칙 > 페이지')
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    // 정렬 기본값은 명세에 없어 최근 가입 순으로 정함(#32)
    public static final String DEFAULT_SORT = "createdAt,desc";

    private static final String STATUS_PATTERN = "PENDING|ACTIVE|WITHDRAWN";
    private static final String SORT_PATTERN = "(createdAt|loginId|lastActiveAt)(,(asc|desc))?";
    private static final String STATUS_MESSAGE = "상태는 PENDING·ACTIVE·WITHDRAWN 중 하나여야 합니다.";
    private static final String SORT_MESSAGE = "정렬은 createdAt·loginId·lastActiveAt 중 하나와 asc·desc로 지정해 주세요.";
    private static final String PAGE_MESSAGE = "페이지는 0 이상이어야 합니다.";
    private static final String SIZE_MESSAGE = "한 페이지 개수는 1~100이어야 합니다.";
    private static final String DESC = "desc";

    public UserSearchRequest {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        status = status == null || status.isBlank() ? null : status;
        sort = sort == null || sort.isBlank() ? DEFAULT_SORT : sort;
        page = page == null ? 0 : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    /** 검증을 통과한 뒤에만 부른다. */
    public UserSortField sortField() {
        return UserSortField.fromParamName(sort.split(",")[0]);
    }

    public boolean isAscending() {
        String[] parts = sort.split(",");
        return parts.length < 2 || !DESC.equals(parts[1]);
    }

    /**
     * 세션 정보를 붙여야 거르거나 정렬할 수 있는 조건인지. 그렇다면 DB 조건에 맞는 계정을 상한까지 가져와 메모리에서 처리한다
     * (FNC-A-002 업무 규칙).
     */
    public boolean isSessionDependent() {
        return active != null || sortField() == UserSortField.LAST_ACTIVE_AT;
    }

    /** 가입 시각 하한(포함). 없으면 null. */
    public LocalDateTime joinedFromInclusive() {
        return joinedFrom == null ? null : joinedFrom.atStartOfDay();
    }

    /** 가입 시각 상한(미포함). joinedTo 그날까지 넣으려고 다음 날 0시로 바꾼다. 없으면 null. */
    public LocalDateTime joinedToExclusive() {
        return joinedTo == null ? null : joinedTo.plusDays(1).atStartOfDay();
    }
}
