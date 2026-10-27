package com.whiteeyes.common.web;

import com.whiteeyes.common.security.ApiRequests;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

/**
 * 서버 오류 처리(/error)를 Accept 헤더가 아니라 원래 요청 경로로 화면·API를 갈라 답한다(FNC-C-003, conventions.md '인증 오류').
 * 기본 오류 처리(BasicErrorController)는 Accept에 text/html이 없으면 화면 경로에도 JSON을 보내서 이것으로 바꾼다.
 * 화면은 공통 오류 화면(문구는 ErrorPageViewResolver), API는 기본 오류 처리와 같은 JSON이다.
 * 웹 없이 띄우면(web-application-type=none) 오류 처리 빈이 없어 웹 앱일 때만 등록한다(LoginPageController와 같음).
 */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ErrorPageController extends AbstractErrorController {

    private final JsonMapper jsonMapper;

    public ErrorPageController(
            ErrorAttributes errorAttributes, List<ErrorViewResolver> errorViewResolvers, JsonMapper jsonMapper) {
        super(errorAttributes, errorViewResolvers);
        this.jsonMapper = jsonMapper;
    }

    @RequestMapping
    public ModelAndView error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = getStatus(request);
        Map<String, Object> model = getErrorAttributes(request, ErrorAttributeOptions.defaults());
        if (ApiRequests.isApi(originalPath(request))) {
            ModelAndView modelAndView = new ModelAndView(new JacksonJsonView(jsonMapper), model);
            modelAndView.setStatus(status);
            return modelAndView;
        }
        response.setStatus(status.value());
        ModelAndView modelAndView = resolveErrorView(request, response, status, model);
        return modelAndView != null ? modelAndView : new ModelAndView("error", model);
    }

    // 오류 처리로 넘어오기 전 요청의 경로(컨텍스트 경로를 뗀 것). /error를 직접 부르면 /error다
    private static String originalPath(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri instanceof String original ? original : request.getRequestURI();
        String contextPath = request.getContextPath();
        return path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;
    }
}
