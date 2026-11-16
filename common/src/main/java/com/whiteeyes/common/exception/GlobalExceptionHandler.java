package com.whiteeyes.common.exception;

import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
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
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    // 화면 요청에서 보여 줄 오류 템플릿 이름
    private static final String ERROR_VIEW = "error";
    // JSON 응답에서 오류 본문을 담는 모델 키. render()에서 이 키의 값만 본문으로 내보낸다
    private static final String JSON_MODEL_KEY = "error";
    // 숫자 칸에 글자를 넣는 등 타입 변환이 실패했을 때 보여 줄 문구
    private static final String TYPE_MISMATCH_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String REQUIRED_MESSAGE = "필수입니다.";
    // /internal/ 은 분석 서버가 부르는 내부 API라 화면이 아니라 JSON이 필요하다
    private static final List<String> API_PATH_PREFIXES = List.of("/api/", "/internal/");

    // 오류 응답을 JSON으로 만드는 도구(앱 전체 설정과 같은 것을 쓴다)
    private final JsonMapper jsonMapper;

    /**
     * 서비스가 던진 업무 오류를 처리한다. 오류 코드가 정한 상태와 메시지로 응답하고, 상태 종류에 따라 로그 수준을 달리한다.
     */
    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusiness(BusinessException ex, HttpServletRequest request, HttpServletResponse response) {
        // 응답 상태·코드를 정하는 정보
        ErrorCode errorCode = ex.getErrorCode();
        // 로그에 어느 요청에서 난 오류인지 남기려고 꺼낸다
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

    /** 폼·쿼리 파라미터 바인딩/검증 실패(BindException)를 입력 오류(400)로 바꾼다. 칸별 오류 목록을 함께 담는다. */
    @ExceptionHandler(BindException.class)
    public ModelAndView handleBind(BindException ex, HttpServletRequest request, HttpServletResponse response) {
        return renderInvalidInput(request, response, toFieldErrors(ex.getFieldErrors()));
    }

    /** 메서드 파라미터에 직접 건 검증(@RequestParam·@PathVariable의 @Min 등) 실패를 입력 오류(400)로 바꾼다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ModelAndView handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request, HttpServletResponse response) {
        // 파라미터마다 나온 검증 결과를 하나의 칸별 오류 목록으로 펼친다
        List<FieldErrorResponse> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> toFieldErrors(result).stream())
                .toList();
        return renderInvalidInput(request, response, fieldErrors);
    }

    /** 경로·쿼리 값을 타입(숫자·날짜 등)으로 바꾸지 못했을 때의 입력 오류(400). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request, HttpServletResponse response) {
        List<FieldErrorResponse> fieldErrors = List.of(new FieldErrorResponse(ex.getName(), TYPE_MISMATCH_MESSAGE));
        return renderInvalidInput(request, response, fieldErrors);
    }

    /** 필수 쿼리 파라미터가 빠졌을 때의 입력 오류(400). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ModelAndView handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request, HttpServletResponse response) {
        List<FieldErrorResponse> fieldErrors = List.of(new FieldErrorResponse(ex.getParameterName(), REQUIRED_MESSAGE));
        return renderInvalidInput(request, response, fieldErrors);
    }

    /** JSON 본문을 읽을 수 없을 때(형식 깨짐, enum 값 오류 등)의 입력 오류(400). 어느 칸인지 알 수 없어 칸별 오류는 비운다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ModelAndView handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request, HttpServletResponse response) {
        log.debug("읽을 수 없는 요청 본문 uri={}", request.getRequestURI());
        return renderInvalidInput(request, response, null);
    }

    /** 다른 서버(분석 서버·외부 API) 호출 중 연결·시간 초과가 난 경우를 502로 바꾼다. */
    @ExceptionHandler(ResourceAccessException.class)
    public ModelAndView handleResourceAccess(
            ResourceAccessException ex, HttpServletRequest request, HttpServletResponse response) {
        // 다른 서버 주소가 응답에 새지 않게 기본 문구만 보낸다. 재시도 대상이라 WARN(code-conventions.md 7-3)
        log.warn("외부 서버 연결 실패 uri={}", request.getRequestURI(), ex);
        ErrorCode errorCode = ErrorCode.EXTERNAL_UNAVAILABLE;
        return render(
                request, response, errorCode.getStatus(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }

    /** 이미 끊긴 연결에 응답을 쓰려다 난 예외. 쓸 곳이 없으므로 빈 결과를 돌려 추가 응답을 만들지 않는다. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ModelAndView handleClientGone(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        // SSE 등에서 브라우저가 먼저 연결을 끊은 경우라 응답을 쓸 곳이 없다
        log.debug("클라이언트 연결 끊김 uri={}", request.getRequestURI());
        return new ModelAndView();
    }

    /** 비동기(SSE 등) 요청이 시간 초과로 끝난 경우를 처리한다. */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ModelAndView handleAsyncTimeout(
            AsyncRequestTimeoutException ex, HttpServletRequest request, HttpServletResponse response) {
        // SSE처럼 오래 열어 두는 연결은 시간이 지나면 끊기는 것이 정상이라 오류 로그를 남기지 않는다
        log.debug("비동기 요청 시간 초과 uri={}", request.getRequestURI());
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return render(
                request, response, ex.getStatusCode(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }

    /**
     * 권한·인증 예외는 보안 필터가 401·403으로 처리해야 하므로 500으로 바꾸지 않고 그대로 다시 던진다(FNC-C-003).
     */
    // 이 처리기가 없으면 아래 handleOther(Exception)가 이 예외들까지 잡아 500으로 바꿔 버린다. 그래서 일부러 잡아서 그대로 다시 던진다
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(Exception ex) throws Exception {
        throw ex;
    }

    /** 위 처리기가 맡지 않은 모든 예외의 마지막 안전망. Spring이 상태를 정한 예외는 그 상태로, 나머지는 500으로 응답한다. */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleOther(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        // 없는 경로(404)·지원하지 않는 메서드(405)·비동기 시간 초과(503) 등 Spring이 상태를 정한 오류는 그 상태를 따른다
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            // Spring 자체 예외(404·405 등)가 가진 상태를 응답에 그대로 쓴다
            HttpStatusCode status = springError.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("요청 처리 오류 status={} uri={}", status.value(), request.getRequestURI(), ex);
            } else {
                log.debug("요청 오류 status={} uri={}", status.value(), request.getRequestURI());
            }
            // 405의 Allow 헤더처럼 예외가 요구하는 응답 헤더도 함께 내려보낸다
            springError
                    .getHeaders()
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

    /** 필드 오류 목록을 응답용 칸별 오류(칸 이름·메시지)로 옮긴다. */
    private List<FieldErrorResponse> toFieldErrors(List<FieldError> errors) {
        return errors.stream()
                // 변환 실패의 기본 메시지는 영어 예외 내용이라 화면용 문장으로 바꾼다
                .map(error -> new FieldErrorResponse(
                        error.getField(), error.isBindingFailure() ? TYPE_MISMATCH_MESSAGE : error.getDefaultMessage()))
                .toList();
    }

    /** 파라미터 한 개의 검증 결과를 칸별 오류로 옮긴다. 객체 파라미터면 필드 오류로, 단일 값이면 파라미터 이름으로 오류를 붙인다. */
    private List<FieldErrorResponse> toFieldErrors(ParameterValidationResult result) {
        // 객체(@Valid 대상)의 필드 오류는 칸 이름을 그대로 쓴다
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

    /** 입력 오류(INVALID_INPUT)를 만들어 응답한다. 칸별 오류가 없으면 null을 넘긴다. */
    private ModelAndView renderInvalidInput(
            HttpServletRequest request, HttpServletResponse response, List<FieldErrorResponse> fieldErrors) {
        ErrorCode errorCode = ErrorCode.INVALID_INPUT;
        return render(
                request,
                response,
                errorCode.getStatus(),
                new ErrorResponse(errorCode.name(), errorCode.getMessage(), fieldErrors));
    }

    /**
     * 오류를 응답으로 만든다. API 요청이면 JSON 본문, 화면 요청이면 오류 화면(템플릿)으로 돌려주고 상태 코드를 설정한다.
     */
    private ModelAndView render(
            HttpServletRequest request, HttpServletResponse response, HttpStatusCode status, ErrorResponse body) {
        // SSE처럼 응답을 이미 보내기 시작했으면 그 위에 오류 응답을 다시 쓸 수 없다
        if (response.isCommitted()) {
            log.debug("응답을 이미 보낸 뒤 오류 code={} uri={}", body.code(), request.getRequestURI());
            return new ModelAndView();
        }
        // 요청 종류에 따라 JSON 뷰 또는 오류 화면 뷰가 들어간다
        ModelAndView modelAndView;
        if (isApiRequest(request)) {
            JacksonJsonView jsonView = new JacksonJsonView(jsonMapper);
            // 경로 변수 등 다른 모델 값이 섞여도 본문에는 오류 응답만 나가게 한다
            jsonView.setModelKey(JSON_MODEL_KEY);
            jsonView.setExposePathVariables(false);
            jsonView.setExtractValueFromSingleKeyModel(true);
            modelAndView = new ModelAndView(jsonView, JSON_MODEL_KEY, body);
        } else {
            // 화면 요청: error 템플릿에 상태·코드·메시지를 모델로 넘긴다
            modelAndView = new ModelAndView(ERROR_VIEW);
            modelAndView.addObject("status", status.value());
            modelAndView.addObject("code", body.code());
            modelAndView.addObject("message", body.message());
        }
        modelAndView.setStatus(status);
        return modelAndView;
    }

    /** 경로가 /api/ 또는 /internal/ 로 시작하면 API 요청으로 본다. 앱이 컨텍스트 경로 아래에서 돌 수 있어 그 부분을 뺀 경로로 비교한다. */
    private boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return API_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** Spring이 정한 HTTP 상태를 이 서비스의 오류 코드로 대응시킨다. 목록에 없는 4xx는 입력 오류, 5xx는 서버 오류로 본다. */
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
