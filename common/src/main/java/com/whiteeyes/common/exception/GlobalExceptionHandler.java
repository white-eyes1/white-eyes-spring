package com.whiteeyes.common.exception;

import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
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
    public ModelAndView handleBusiness(
            BusinessException ex, HttpServletRequest request, HttpServletResponse response) {
        ErrorCode errorCode = ex.getErrorCode();
        String uri = request.getRequestURI();
        // 외부 연결 실패(502)는 재시도로 풀릴 수 있어 ResourceAccessException과 같이 WARN, 그 밖의 5xx는 사람이
        // 봐야 하는 오류라 ERROR이고 둘 다 원인 추적을 위해 스택을 남긴다. 4xx는 예상된 업무 오류라 감싼 원인이
        // 있어도 스택 없이 WARN이다(code-conventions.md 7-3)
        if (errorCode.getStatus() == HttpStatus.BAD_GATEWAY) {
            log.warn("업무 오류 code={} uri={}", errorCode, uri, ex);
        } else if (errorCode.getStatus().is5xxServerError()) {
            log.error("업무 오류 code={} uri={}", errorCode, uri, ex);
        } else {
            log.warn("업무 오류 code={} uri={}", errorCode, uri);
        }
        return render(request, response, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), ex.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    public ModelAndView handleBind(BindException ex, HttpServletRequest request, HttpServletResponse response) {
        return renderInvalidInput(request, response, toFieldErrors(ex.getFieldErrors()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ModelAndView handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request, HttpServletResponse response) {
        List<FieldErrorResponse> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> toFieldErrors(result).stream())
                .toList();
        return renderInvalidInput(request, response, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request, HttpServletResponse response) {
        List<FieldErrorResponse> fieldErrors = List.of(new FieldErrorResponse(ex.getName(), TYPE_MISMATCH_MESSAGE));
        return renderInvalidInput(request, response, fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ModelAndView handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request, HttpServletResponse response) {
        List<FieldErrorResponse> fieldErrors = List.of(new FieldErrorResponse(ex.getParameterName(), REQUIRED_MESSAGE));
        return renderInvalidInput(request, response, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ModelAndView handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request, HttpServletResponse response) {
        log.debug("읽을 수 없는 요청 본문 uri={}", request.getRequestURI());
        return renderInvalidInput(request, response, null);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ModelAndView handleResourceAccess(
            ResourceAccessException ex, HttpServletRequest request, HttpServletResponse response) {
        // 다른 서버 주소가 응답에 새지 않게 기본 문구만 보낸다. 재시도 대상이라 WARN(code-conventions.md 7-3)
        log.warn("외부 서버 연결 실패 uri={}", request.getRequestURI(), ex);
        ErrorCode errorCode = ErrorCode.EXTERNAL_UNAVAILABLE;
        return render(
                request, response, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ModelAndView handleClientGone(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        // SSE 등에서 브라우저가 먼저 연결을 끊은 경우라 응답을 쓸 곳이 없다
        log.debug("클라이언트 연결 끊김 uri={}", request.getRequestURI());
        return new ModelAndView();
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ModelAndView handleAsyncTimeout(
            AsyncRequestTimeoutException ex, HttpServletRequest request, HttpServletResponse response) {
        // SSE처럼 오래 열어 두는 연결은 시간이 지나면 끊기는 것이 정상이라 오류 로그를 남기지 않는다
        log.debug("비동기 요청 시간 초과 uri={}", request.getRequestURI());
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return render(
                request, response, ex.getStatusCode(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleOther(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        // 없는 경로(404)·지원하지 않는 메서드(405)·비동기 시간 초과(503) 등 Spring이 상태를 정한 오류는 그 상태를 따른다
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("요청 처리 오류 status={} uri={}", status.value(), request.getRequestURI(), ex);
            } else {
                log.debug("요청 오류 status={} uri={}", status.value(), request.getRequestURI());
            }
            springError.getHeaders()
                    .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
            ErrorCode errorCode = toErrorCode(status);
            return render(request, response, status, ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
        }
        // 내부 정보가 새지 않게 응답에는 기본 문구만 보내고 원인은 로그에만 남긴다
        log.error("처리하지 못한 예외 uri={}", request.getRequestURI(), ex);
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return render(
                request, response, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
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
        String parameterName = resolveParameterName(result.getMethodParameter());
        return result.getResolvableErrors().stream()
                .map(error -> new FieldErrorResponse(parameterName, error.getDefaultMessage()))
                .toList();
    }

    // common은 -parameters 없이 컴파일되어 매개변수 이름이 비어 있을 수 있으므로 애너테이션에 적힌 이름을 먼저 쓴다
    private String resolveParameterName(MethodParameter parameter) {
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String name = StringUtils.hasText(pathVariable.name()) ? pathVariable.name() : pathVariable.value();
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            String name = StringUtils.hasText(requestParam.name()) ? requestParam.name() : requestParam.value();
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        return Objects.requireNonNullElse(parameter.getParameterName(), "");
    }

    private ModelAndView renderInvalidInput(
            HttpServletRequest request, HttpServletResponse response, List<FieldErrorResponse> fieldErrors) {
        ErrorCode errorCode = ErrorCode.INVALID_INPUT;
        return render(
                request,
                response,
                errorCode.getStatus(),
                new ErrorResponse(errorCode.name(), errorCode.getMessage(), fieldErrors));
    }

    private ModelAndView render(
            HttpServletRequest request, HttpServletResponse response, HttpStatusCode status, ErrorResponse body) {
        // SSE처럼 응답을 이미 보내기 시작했으면 그 위에 오류 응답을 다시 쓸 수 없다
        if (response.isCommitted()) {
            log.debug("응답을 이미 보낸 뒤 오류 code={} uri={}", body.code(), request.getRequestURI());
            return new ModelAndView();
        }
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
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.STATUS_CONFLICT;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_INPUT;
        };
    }
}
