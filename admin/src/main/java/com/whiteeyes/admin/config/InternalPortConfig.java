package com.whiteeyes.admin.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 포트를 열 필요가 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalPortConfig {

    private static final String HTTP_PROTOCOL = "org.apache.coyote.http11.Http11NioProtocol";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalPortConnector(
            @Value("${internal-api.port}") int internalPort) {
        return factory -> {
            Connector connector = new Connector(HTTP_PROTOCOL);
            connector.setPort(internalPort);
            factory.addAdditionalConnectors(connector);
        };
    }

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
