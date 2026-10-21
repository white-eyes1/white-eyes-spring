package com.whiteeyes.common.web;

import com.whiteeyes.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

/**
 * 컨트롤러 앞(보안 필터 등)에서 난 화면 오류도 공통 오류 화면(templates/error.html)으로 보여 준다(FNC-C-003, FNC-C-027).
 * 서버 기본 오류 정보에는 문구가 없어 403에도 '일시적인 오류' 문구가 나오므로, 문구는 ErrorCode의 기본 문장을 쓴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorPageViewResolver implements ErrorViewResolver {

    private static final String ERROR_VIEW = "error";

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        ErrorCode errorCode = toErrorCode(status);
        return new ModelAndView(
                ERROR_VIEW,
                Map.of("status", status.value(), "code", errorCode.name(), "message", errorCode.getMessage()),
                status);
    }

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
