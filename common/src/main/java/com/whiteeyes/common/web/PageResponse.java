package com.whiteeyes.common.web;

import java.util.List;

/**
 * 목록 API 공통 페이지 응답(conventions.md '공통 API 규칙 > 페이지'). page는 0부터 센다.
 * truncated는 명세에 없던 칸으로, 메모리에서 거르고 정렬하는 목록이 처리 상한에 걸려 일부만 다뤘으면 true다(FNC-A-002).
 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalElements, int totalPages, boolean truncated) {

    /** 조건에 맞는 전체를 센 목록. */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        return of(items, page, size, totalElements, false);
    }

    /** truncated가 true면 totalElements는 상한 안에서 센 값이다. */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements, boolean truncated) {
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(items, page, size, totalElements, totalPages, truncated);
    }
}
