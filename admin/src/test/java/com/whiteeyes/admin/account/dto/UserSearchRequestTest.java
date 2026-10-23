package com.whiteeyes.admin.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 사용자 목록 조회 조건의 기본값과 검증(FNC-A-002). */
class UserSearchRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("FNC-A-002 비운 값은 기본값: 0페이지, 20개, 최근 가입 순")
    void defaults() {
        UserSearchRequest request = new UserSearchRequest(" ", "", null, null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.keyword()).isNull();
        assertThat(request.status()).isNull();
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.sortField()).isEqualTo(AccountSortField.CREATED_AT);
        assertThat(request.isAscending()).isFalse();
        assertThat(request.isSessionDependent()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"createdAt,asc", "loginId,desc", "lastActiveAt", "loginId"})
    @DisplayName("FNC-A-002 정렬 기준과 방향을 읽는다. 방향을 빼면 asc")
    void sort_valid(String sort) {
        UserSearchRequest request = withSort(sort);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.sortField().getParamName()).isEqualTo(sort.split(",")[0]);
        assertThat(request.isAscending()).isEqualTo(!sort.endsWith(",desc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name,asc", "createdAt,up", "createdAt,", "CREATEDAT"})
    @DisplayName("FNC-A-002 정해진 정렬 기준·방향이 아니면 sort 오류")
    void sort_invalid(String sort) {
        assertThat(fieldsOf(validator.validate(withSort(sort)))).containsExactly("sort");
    }

    @Test
    @DisplayName("FNC-A-002 정해진 상태 값이 아니면 status 오류")
    void status_invalid() {
        UserSearchRequest request = new UserSearchRequest(null, "DELETED", null, null, null, null, null, null);

        assertThat(fieldsOf(validator.validate(request))).containsExactly("status");
    }

    @Test
    @DisplayName("FNC-A-002 page는 0 이상, size는 1~100")
    void pageAndSize_bounds() {
        assertThat(fieldsOf(validator.validate(withPage(-1, 20)))).containsExactly("page");
        assertThat(fieldsOf(validator.validate(withPage(0, 0)))).containsExactly("size");
        assertThat(fieldsOf(validator.validate(withPage(0, 101)))).containsExactly("size");
        assertThat(validator.validate(withPage(0, 100))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-002 가입일 끝이 시작보다 앞서면 joinedTo 오류, 같은 날은 허용")
    void joinedRange() {
        LocalDate day = LocalDate.of(2026, 10, 12);

        assertThat(fieldsOf(validator.validate(withJoined(day, day.minusDays(1))))).containsExactly("joinedTo");
        assertThat(validator.validate(withJoined(day, day))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-002 가입일 끝 날짜도 포함하도록 다음 날 0시 미만으로 바꾼다")
    void joinedRange_toDayInclusive() {
        LocalDate day = LocalDate.of(2026, 10, 12);
        UserSearchRequest request = withJoined(day, day);

        assertThat(request.joinedFromInclusive()).isEqualTo(LocalDateTime.of(2026, 10, 12, 0, 0));
        assertThat(request.joinedToExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 13, 0, 0));
    }

    @Test
    @DisplayName("FNC-A-002 active 필터나 lastActiveAt 정렬이면 세션 정보를 붙인 뒤 처리한다")
    void sessionDependent() {
        assertThat(new UserSearchRequest(null, null, null, null, true, null, null, null).isSessionDependent())
                .isTrue();
        assertThat(withSort("lastActiveAt,desc").isSessionDependent()).isTrue();
        assertThat(withSort("loginId,asc").isSessionDependent()).isFalse();
    }

    private UserSearchRequest withSort(String sort) {
        return new UserSearchRequest(null, null, null, null, null, sort, null, null);
    }

    private UserSearchRequest withPage(int page, int size) {
        return new UserSearchRequest(null, null, null, null, null, null, page, size);
    }

    private UserSearchRequest withJoined(LocalDate from, LocalDate to) {
        return new UserSearchRequest(null, null, from, to, null, null, null, null);
    }

    private Set<String> fieldsOf(Set<ConstraintViolation<UserSearchRequest>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
