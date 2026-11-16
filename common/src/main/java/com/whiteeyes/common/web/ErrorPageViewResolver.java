package com.whiteeyes.common.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import com.whiteeyes.common.exception.ErrorCode;

/**
 * 컨트롤러 앞(보안 필터 등)에서 난 화면 오류도 공통 오류 화면(templates/error.html)으로 보여 준다(FNC-C-003, FNC-C-027).
 * 서버 기본 오류 정보에는 문구가 없어 403에도 '일시적인 오류' 문구가 나오므로, 문구는 ErrorCode의 기본 문장을 쓴다.
 */
@Component
// 오류 화면 해석기가 여러 개일 때 가장 먼저 이 구현이 쓰이게 한다
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorPageViewResolver implements ErrorViewResolver {

    // 공통 오류 템플릿 이름
    private static final String ERROR_VIEW = "error";

    /** 상태 코드에 맞는 오류 코드·기본 문구를 템플릿 모델에 담아 공통 오류 화면을 돌려준다. */
    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        ErrorCode errorCode = toErrorCode(status);
        return new ModelAndView(
                ERROR_VIEW,
                Map.of("status", status.value(), "code", errorCode.name(), "message", errorCode.getMessage()),
                status);
    }

    /** HTTP 상태를 오류 코드로 대응시킨다. 대응이 없는 4xx는 입력 오류, 5xx는 서버 오류로 본다. */
    private ErrorCode toErrorCode(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_INPUT;
        };
    }
}
