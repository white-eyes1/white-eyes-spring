package com.whiteeyes.common.exception;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.JacksonJsonView;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.web.ErrorResponse;
import com.whiteeyes.common.web.ErrorResponse.FieldErrorResponse;

/**
 * 컨트롤러에서 난 예외를 공통 오류 응답(API)과 오류 화면(화면 요청)으로 바꾼다(FNC-C-027).
 * API 요청인지는 경로로 판단한다(conventions.md '인증 오류').
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERROR_VIEW = "error";
    private static final String JSON_MODEL_KEY = "error";
    private static final String TYPE_MISMATCH_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String REQUIRED_MESSAGE = "필수입니다.";
    // /internal/ 은 분석 서버가 부르는 내부 API라 화면이 아니라 JSON이 필요하다
    private static final List<String> API_PATH_PREFIXES = List.of("/api/", "/internal/");

    private final JsonMapper jsonMapper;

    public GlobalExceptionHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("업무 오류 code={} uri={}", errorCode, request.getRequestURI());
        return render(request, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), ex.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    public ModelAndView handleBind(BindException ex, HttpServletRequest request) {
        return renderInvalidInput(request, toFieldErrors(ex.getFieldErrors()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ModelAndView handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldErrorResponse> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> toFieldErrors(result).stream())
                .toList();
        return renderInvalidInput(request, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return renderInvalidInput(request, List.of(new FieldErrorResponse(ex.getName(), TYPE_MISMATCH_MESSAGE)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ModelAndView handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return renderInvalidInput(request, List.of(new FieldErrorResponse(ex.getParameterName(), REQUIRED_MESSAGE)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ModelAndView handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("읽을 수 없는 요청 본문 uri={}", request.getRequestURI());
        return renderInvalidInput(request, null);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ModelAndView handleResourceAccess(ResourceAccessException ex, HttpServletRequest request) {
        // 다른 서버 주소가 응답에 새지 않게 기본 문구만 보낸다. 재시도 대상이라 WARN(code-conventions.md 7-3)
        log.warn("외부 서버 연결 실패 uri={}", request.getRequestURI(), ex);
        ErrorCode errorCode = ErrorCode.EXTERNAL_UNAVAILABLE;
        return render(request, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ModelAndView handleClientGone(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        // SSE 등에서 브라우저가 먼저 연결을 끊은 경우라 응답을 쓸 곳이 없다
        log.debug("클라이언트 연결 끊김 uri={}", request.getRequestURI());
        return new ModelAndView();
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleOther(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("응답을 이미 보낸 뒤 예외 uri={}", request.getRequestURI(), ex);
            return new ModelAndView();
        }
        // 없는 경로(404)·지원하지 않는 메서드(405)·비동기 시간 초과(503) 등 Spring이 상태를 정한 오류는 그 상태를 따른다
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            springError.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
            ErrorCode errorCode = toErrorCode(status);
            log.debug("요청 오류 status={} uri={}", status.value(), request.getRequestURI());
            return render(request, status, ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
        }
        // 내부 정보가 새지 않게 응답에는 기본 문구만 보내고 원인은 로그에만 남긴다
        log.error("처리하지 못한 예외 uri={}", request.getRequestURI(), ex);
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return render(request, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }

    private List<FieldErrorResponse> toFieldErrors(List<FieldError> errors) {
        return errors.stream()
                // 변환 실패의 기본 메시지는 영어 예외 내용이라 화면용 문장으로 바꾼다
                .map(error -> new FieldErrorResponse(
                        error.getField(), error.isBindingFailure() ? TYPE_MISMATCH_MESSAGE : error.getDefaultMessage()))
                .toList();
    }

    private List<FieldErrorResponse> toFieldErrors(ParameterValidationResult result) {
        if (result instanceof ParameterErrors parameterErrors) {
            return toFieldErrors(parameterErrors.getFieldErrors());
        }
        String parameterName = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream()
                .map(error -> new FieldErrorResponse(parameterName, error.getDefaultMessage()))
                .toList();
    }

    private ModelAndView renderInvalidInput(HttpServletRequest request, List<FieldErrorResponse> fieldErrors) {
        ErrorCode errorCode = ErrorCode.INVALID_INPUT;
        return render(
                request,
                errorCode.getStatus(),
                new ErrorResponse(errorCode.name(), errorCode.getMessage(), fieldErrors));
    }

    private ModelAndView render(HttpServletRequest request, HttpStatusCode status, ErrorResponse body) {
        ModelAndView modelAndView;
        if (isApiRequest(request)) {
            JacksonJsonView jsonView = new JacksonJsonView(jsonMapper);
            // 경로 변수 등 다른 모델 값이 섞여도 본문에는 오류 응답만 나가게 한다
            jsonView.setModelKey(JSON_MODEL_KEY);
            jsonView.setExposePathVariables(false);
            jsonView.setExtractValueFromSingleKeyModel(true);
            modelAndView = new ModelAndView(jsonView, JSON_MODEL_KEY, body);
        } else {
            modelAndView = new ModelAndView(ERROR_VIEW);
            modelAndView.addObject("status", status.value());
            modelAndView.addObject("code", body.code());
            modelAndView.addObject("message", body.message());
        }
        modelAndView.setStatus(status);
        return modelAndView;
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return API_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private ErrorCode toErrorCode(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return ErrorCode.INVALID_INPUT;
    }
}
