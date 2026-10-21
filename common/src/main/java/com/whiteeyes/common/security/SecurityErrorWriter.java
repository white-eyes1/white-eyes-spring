package com.whiteeyes.common.security;

import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.web.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 필터 단계(컨트롤러 앞)에서 난 오류를 공통 오류 응답으로 쓴다(FNC-C-003, conventions.md '오류 응답').
 * 이 단계의 오류는 GlobalExceptionHandler를 거치지 않으므로 같은 형식을 여기서 직접 쓴다.
 */
public final class SecurityErrorWriter {

    private SecurityErrorWriter() {}

    public static void write(HttpServletResponse response, JsonMapper jsonMapper, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }
}
