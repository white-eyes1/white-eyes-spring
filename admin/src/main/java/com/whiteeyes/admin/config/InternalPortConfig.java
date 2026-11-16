package com.whiteeyes.admin.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 앱의 내부 통신 포트(8082) 연결(FNC-C-003). 웹 포트(8081)와 같은 앱에 포트만 하나 더 열고,
 * 어느 포트에서 어느 경로를 받는지는 InternalPortFilter가 가른다.
 */
@Configuration
public class InternalPortConfig {

    // 톰캣 커넥터의 HTTP/1.1 NIO 구현 클래스 이름(기본 웹 포트와 같은 방식으로 내부 포트를 열기 위함).
    private static final String HTTP_PROTOCOL = "org.apache.coyote.http11.Http11NioProtocol";

    /**
     * 톰캣에 내부 통신용 커넥터(포트)를 하나 더 추가한다. 웹용 포트와 같은 앱을 공유한다.
     *
     * @param internalPort 내부 통신 포트(internal-api.port, 8082)
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalPortConnector(
            @Value("${internal-api.port}") int internalPort) {
        return factory -> {
            Connector connector = new Connector(HTTP_PROTOCOL);
            connector.setPort(internalPort);
            factory.addAdditionalConnectors(connector);
        };
    }

    /**
     * 포트와 경로의 짝이 맞는지 검사하는 InternalPortFilter를 서블릿 필터로 등록한다.
     *
     * @param internalPort 내부 통신 포트
     * @param jsonMapper 오류 응답을 JSON으로 쓰기 위한 매퍼
     */
    @Bean
    public FilterRegistrationBean<InternalPortFilter> internalPortFilter(
            @Value("${internal-api.port}") int internalPort, JsonMapper jsonMapper) {
        FilterRegistrationBean<InternalPortFilter> registration =
                new FilterRegistrationBean<>(new InternalPortFilter(internalPort, jsonMapper));
        // 보안 필터 체인보다 앞에서 돌아야 한다
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
