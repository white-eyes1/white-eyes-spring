package com.whiteeyes.admin.account.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
// 가입일 끝이 시작보다 앞서지 않는지 두 필드를 함께 검사한다(JoinedDateRangeValidator)
@JoinedDateRange
// 쿼리 문자열 값을 그대로 받는 레코드다. 값이 없으면 null로 오며, 기본값은 아래 compact 생성자가 채운다
public record UserSearchRequest(
        String keyword,
        @Pattern(regexp = STATUS_PATTERN, message = STATUS_MESSAGE) String status,
        // 가입일은 yyyy-MM-dd 날짜 형식으로 받는다
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
        Boolean active,
        // 정렬은 '기준' 또는 '기준,방향' 형식만 허용한다
        @Pattern(regexp = SORT_PATTERN, message = SORT_MESSAGE) String sort,
        @Min(value = 0, message = PAGE_MESSAGE) Integer page,
        @Min(value = 1, message = SIZE_MESSAGE) @Max(value = MAX_SIZE, message = SIZE_MESSAGE) Integer size) {

    // 페이지 기본값·최대값(conventions.md '공통 API 규칙 > 페이지')
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    // 정렬 기본값은 명세에 없어 최근 가입 순으로 정함(#32)
    public static final String DEFAULT_SORT = "createdAt,desc";

    // 검증용 정규식과 오류 문구. 정규식과 문구가 서로 맞아야 하므로 한곳에 모아 둔다
    private static final String STATUS_PATTERN = "PENDING|ACTIVE|WITHDRAWN";
    private static final String SORT_PATTERN = "(createdAt|loginId|lastActiveAt)(,(asc|desc))?";
    private static final String STATUS_MESSAGE = "상태는 PENDING·ACTIVE·WITHDRAWN 중 하나여야 합니다.";
    private static final String SORT_MESSAGE = "정렬은 createdAt·loginId·lastActiveAt 중 하나와 asc·desc로 지정해 주세요.";
    private static final String PAGE_MESSAGE = "페이지는 0 이상이어야 합니다.";
    private static final String SIZE_MESSAGE = "한 페이지 개수는 1~100이어야 합니다.";
    // 정렬 방향 문자열. 방향이 없거나 desc가 아니면 오름차순으로 본다(isAscending)
    private static final String DESC = "desc";

    /** 생성할 때 비었거나 공백뿐인 값을 null로 정리하고, 빠진 값에 기본값을 채운다. */
    public UserSearchRequest {
        // 검색어: 비었으면 null(조건 없음), 아니면 앞뒤 공백을 지운다
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        // 상태: 빈 문자열도 '전체'로 취급해 null로 맞춘다
        status = status == null || status.isBlank() ? null : status;
        // 정렬: 없으면 최근 가입 순
        sort = sort == null || sort.isBlank() ? DEFAULT_SORT : sort;
        // 페이지: 없으면 첫 페이지
        page = page == null ? 0 : page;
        // 크기: 없으면 기본 크기
        size = size == null ? DEFAULT_SIZE : size;
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

    /**
     * 세션 정보를 붙여야 거르거나 정렬할 수 있는 조건인지. 그렇다면 DB 조건에 맞는 계정을 상한까지 가져와 메모리에서 처리한다
     * (FNC-A-002 업무 규칙).
     */
    public boolean isSessionDependent() {
        return active != null || sortField() == AccountSortField.LAST_ACTIVE_AT;
    }

    /** 가입 시각 하한(포함). 없으면 null. */
    public LocalDateTime joinedFromInclusive() {
        // 그날 0시부터. 이 시각 이상(>=)이면 조건에 맞는다
        return joinedFrom == null ? null : joinedFrom.atStartOfDay();
    }

    /** 가입 시각 상한(미포함). joinedTo 그날까지 넣으려고 다음 날 0시로 바꾼다. 없으면 null. */
    public LocalDateTime joinedToExclusive() {
        // 끝 날짜를 포함하려고 '다음 날 0시 미만(<)'으로 바꾼다. 23:59:59.999 같은 경계 값 문제를 피한다
        return joinedTo == null ? null : joinedTo.plusDays(1).atStartOfDay();
    }
}
