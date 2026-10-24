package com.whiteeyes.common.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 내 정보 수정 요청의 입력 검사 경계값(FNC-C-006, conventions.md '입력값 규칙').
 * 보내지 않은(null) 필드는 검사하지 않는다. PATCH는 보낸 필드만 고치기 때문이다.
 */
class MyInfoUpdateRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(MyInfoUpdateRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("FNC-C-006 아무 필드도 보내지 않아도 검사를 통과한다")
    void emptyRequest_passes() {
        assertThat(invalidFields(new MyInfoUpdateRequest(null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("FNC-C-006 이름은 1자와 50자까지 통과한다")
    void name_boundaryInside_passes() {
        assertThat(invalidFields(new MyInfoUpdateRequest("가", null, null))).isEmpty();
        assertThat(invalidFields(new MyInfoUpdateRequest("가".repeat(50), null, null)))
                .isEmpty();
    }

    @ParameterizedTest(name = "[{index}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("FNC-C-006 이름이 비었거나 공백뿐이면 name 오류")
    void name_blank_fails(String name) {
        assertThat(invalidFields(new MyInfoUpdateRequest(name, null, null))).containsExactly("name");
    }

    @Test
    @DisplayName("FNC-C-006 이름이 51자면 name 오류")
    void name_tooLong_fails() {
        assertThat(invalidFields(new MyInfoUpdateRequest("가".repeat(51), null, null)))
                .containsExactly("name");
    }

    @Test
    @DisplayName("FNC-C-006 메일은 빈 값(지우기)과 100자까지 통과한다")
    void email_boundaryInside_passes() {
        String hundred = "a".repeat(88) + "@example.com"; // 88 + 12 = 100자
        assertThat(hundred).hasSize(100);
        assertThat(invalidFields(new MyInfoUpdateRequest(null, "", null))).isEmpty();
        assertThat(invalidFields(new MyInfoUpdateRequest(null, hundred, null))).isEmpty();
    }

    @Test
    @DisplayName("FNC-C-006 메일이 101자면 email 오류")
    void email_tooLong_fails() {
        String over = "a".repeat(89) + "@example.com";
        assertThat(over).hasSize(101);
        assertThat(invalidFields(new MyInfoUpdateRequest(null, over, null))).containsExactly("email");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"no-at-sign", "a@", "@b.com", "a b@c.com"})
    @DisplayName("FNC-C-006 메일 형식이 아니면 email 오류")
    void email_badFormat_fails(String email) {
        assertThat(invalidFields(new MyInfoUpdateRequest(null, email, null))).containsExactly("email");
    }

    @Test
    @DisplayName("FNC-C-006 연락처는 빈 값(지우기)과 숫자·-로 20자까지 통과한다")
    void phone_boundaryInside_passes() {
        assertThat(invalidFields(new MyInfoUpdateRequest(null, null, ""))).isEmpty();
        assertThat(invalidFields(new MyInfoUpdateRequest(null, null, "010-1234-5678"))).isEmpty();
        assertThat(invalidFields(new MyInfoUpdateRequest(null, null, "1".repeat(20)))).isEmpty();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"111111111111111111111", "010 1234 5678", "010-1234-abcd", "+82-10-1234"})
    @DisplayName("FNC-C-006 연락처가 21자이거나 숫자·-가 아닌 문자가 있으면 phone 오류")
    void phone_invalid_fails(String phone) {
        assertThat(invalidFields(new MyInfoUpdateRequest(null, null, phone))).containsExactly("phone");
    }

    @Test
    @DisplayName("FNC-C-006 여러 필드가 잘못되면 필드마다 오류가 난다")
    void multipleInvalid_reportsEach() {
        assertThat(invalidFields(new MyInfoUpdateRequest("", "x", "abc"))).containsExactlyInAnyOrder("name", "email", "phone");
    }
}
