package com.whiteeyes.common.security;

import com.whiteeyes.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 권한이 모자란 요청의 처리(FNC-C-003 처리 순서 3): API는 403 공통 오류 응답, 화면은 403 오류 화면이다.
 * 화면은 서버의 오류 처리로 넘기며, 오류 화면의 문구는 common의 ErrorPageViewResolver가 정한다.
 */
public class ForbiddenHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public ForbiddenHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ApiRequests.isApi(request)) {
            SecurityErrorWriter.write(response, jsonMapper, ErrorCode.FORBIDDEN);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
