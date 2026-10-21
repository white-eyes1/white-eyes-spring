package com.whiteeyes.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 서버를 두 포트로 띄워 /internal/** 이 내부 포트에서만 열리는지 본다(FNC-C-003).
 * 8082를 다른 프로그램이 쓰고 있을 수 있어 비어 있는 포트를 찾아 내부 포트로 준다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.context-path=/admin",
            "server.servlet.session.cookie.name=ADMIN_SESSION",
            "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN"
        })
class InternalPortServerTest {

    private static final String TOKEN_HEADER = "X-Internal-Token";
    // 테스트용 application.yml의 값(가짜)
    private static final String TOKEN = "test-internal-token";
    private static final String RESULTS_PATH = "/admin/internal/v1/results";

    private static int internalPort;

    @LocalServerPort
    private int webPort;

    @DynamicPropertySource
    static void internalPort(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            internalPort = socket.getLocalPort();
        }
        registry.add("internal-api.port", () -> internalPort);
    }

    private int post(int port, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[]"));
        if (token != null) {
            builder.header(TOKEN_HEADER, token);
        }
        return HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private int get(int port, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    @DisplayName("FNC-C-003 내부 포트에서 올바른 토큰이면 /internal/** 이 통과하고 토큰이 없으면 401이다")
    void internalPort_acceptsInternalPathWithToken() throws Exception {
        assertThat(post(internalPort, RESULTS_PATH, TOKEN)).isEqualTo(204);
        assertThat(post(internalPort, RESULTS_PATH, null)).isEqualTo(401);
        assertThat(post(internalPort, RESULTS_PATH, "wrong-token")).isEqualTo(401);
    }

    @Test
    @DisplayName("FNC-C-003 웹 포트에서는 올바른 토큰이 있어도 /internal/** 을 받지 않는다")
    void webPort_rejectsInternalPath() throws Exception {
        assertThat(post(webPort, RESULTS_PATH, TOKEN)).isEqualTo(404);
    }

    @Test
    @DisplayName("FNC-C-003 내부 포트에서는 /internal/** 이 아닌 경로를 받지 않는다")
    void internalPort_rejectsOtherPaths() throws Exception {
        assertThat(get(internalPort, "/admin/login")).isEqualTo(404);
    }

    @Test
    @DisplayName("FNC-C-003 웹 포트의 로그인 화면은 그대로 열린다")
    void webPort_loginPageOpen() throws Exception {
        assertThat(get(webPort, "/admin/login")).isEqualTo(200);
    }
}
