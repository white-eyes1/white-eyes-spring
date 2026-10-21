package com.whiteeyes.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

/**
 * 보안 필터 단계의 화면 오류도 공통 오류 화면에 상태에 맞는 문구를 채운다(FNC-C-003).
 */
class ErrorPageViewResolverTest {

    private final ErrorPageViewResolver resolver = new ErrorPageViewResolver();

    private ModelAndView resolve(HttpStatus status) {
        return resolver.resolveErrorView(new MockHttpServletRequest(), status, Map.of());
    }

    @Test
    @DisplayName("FNC-C-003 403은 공통 오류 화면에 '권한이 없습니다.' 문구를 채운다")
    void forbidden_usesForbiddenMessage() {
        ModelAndView view = resolve(HttpStatus.FORBIDDEN);

        assertThat(view.getViewName()).isEqualTo("error");
        assertThat(view.getModel()).containsEntry("status", 403).containsEntry("message", "권한이 없습니다.");
        assertThat(view.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("FNC-C-003 401과 404도 상태에 맞는 문구를 쓴다")
    void unauthorizedAndNotFound_useOwnMessages() {
        assertThat(resolve(HttpStatus.UNAUTHORIZED).getModel()).containsEntry("message", "로그인이 필요합니다.");
        assertThat(resolve(HttpStatus.NOT_FOUND).getModel())
                .containsEntry("message", "요청한 대상을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("FNC-C-003 500대 오류는 내부 정보 없이 기본 문구만 보인다")
    void serverError_usesGenericMessage() {
        ModelAndView view = resolve(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(view.getModel())
                .containsEntry("message", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")
                .doesNotContainKey("trace");
    }
}
