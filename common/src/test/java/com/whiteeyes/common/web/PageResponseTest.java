package com.whiteeyes.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    @DisplayName("전체 개수와 페이지 크기로 전체 페이지 수를 올림해 계산한다")
    void totalPages_roundsUp() {
        assertThat(PageResponse.of(List.of(), 0, 20, 0).totalPages()).isZero();
        assertThat(PageResponse.of(List.of("a"), 0, 20, 1).totalPages()).isEqualTo(1);
        assertThat(PageResponse.of(List.of(), 0, 20, 20).totalPages()).isEqualTo(1);
        assertThat(PageResponse.of(List.of(), 0, 20, 21).totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-002 상한에 걸린 목록만 truncated가 true")
    void truncated() {
        assertThat(PageResponse.of(List.of(), 0, 20, 5).truncated()).isFalse();
        assertThat(PageResponse.of(List.of(), 0, 20, 1000, true).truncated()).isTrue();
    }
}
