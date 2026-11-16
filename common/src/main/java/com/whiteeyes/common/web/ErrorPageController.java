package com.whiteeyes.common.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.autoconfigure.error.AbstractErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.JacksonJsonView;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.security.ApiRequests;

/**
 * 서버 오류 처리(/error)를 Accept 헤더가 아니라 원래 요청 경로로 화면·API를 갈라 답한다(FNC-C-003, conventions.md '인증 오류').
 * 기본 오류 처리(BasicErrorController)는 Accept에 text/html이 없으면 화면 경로에도 JSON을 보내서 이것으로 바꾼다.
 * 화면은 공통 오류 화면(문구는 ErrorPageViewResolver), API는 기본 오류 처리와 같은 JSON이다.
 */
// BasicErrorController 대신 서버 오류 경로(기본 /error)를 받는 컨트롤러. 아래 @RequestMapping의 경로 값은 설정(server.error.path)을 따른다
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ErrorPageController extends AbstractErrorController {

    // API 오류 응답을 JSON 뷰로 만들 때 쓰는 도구
    private final JsonMapper jsonMapper;

    public ErrorPageController(
            ErrorAttributes errorAttributes, List<ErrorViewResolver> errorViewResolvers, JsonMapper jsonMapper) {
        // 부모(AbstractErrorController)가 오류 속성 조회와 오류 화면 해석에 쓸 부품을 받는다
        super(errorAttributes, errorViewResolvers);
        this.jsonMapper = jsonMapper;
    }

    /** 서버가 오류 처리로 넘긴 요청에 응답한다. 원래 요청 경로가 API이면 JSON, 아니면 오류 화면을 돌려준다. */
    @RequestMapping
    public ModelAndView error(HttpServletRequest request, HttpServletResponse response) {
        // 서버가 요청 속성에 담아 둔 원래 오류 상태
        HttpStatus status = getStatus(request);
        // 기본 오류 정보(상태·오류 이름·경로 등). 기본 옵션이라 스택 트레이스나 내부 메시지는 포함하지 않는다
        Map<String, Object> model = getErrorAttributes(request, ErrorAttributeOptions.defaults());
        if (ApiRequests.isApi(originalPath(request))) {
            ModelAndView modelAndView = new ModelAndView(new JacksonJsonView(jsonMapper), model);
            modelAndView.setStatus(status);
            return modelAndView;
        }
        // 화면 요청은 오류 화면을 그리면서 상태 코드도 그대로 유지한다
        response.setStatus(status.value());
        ModelAndView modelAndView = resolveErrorView(request, response, status, model);
        // 해석 가능한 오류 화면이 없으면 기본 error 템플릿으로 보여 준다
        return modelAndView != null ? modelAndView : new ModelAndView("error", model);
    }

    // 오류 처리로 넘어오기 전 요청의 경로(컨텍스트 경로를 뗀 것). /error를 직접 부르면 /error다
    private static String originalPath(HttpServletRequest request) {
        // 서버가 오류 처리로 넘길 때 원래 요청 주소를 넣어 둔 서블릿 속성
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri instanceof String original ? original : request.getRequestURI();
        String contextPath = request.getContextPath();
        // 관리자 앱은 /admin이 컨텍스트 경로라 떼어 내야 /api/ 같은 접두어로 API를 구분할 수 있다
        return path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;
    }
}
