package com.whiteeyes.admin.camera;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 분석 서버 역할의 가짜 HTTP 서버(캠 가동·정지 테스트가 같이 쓴다). 한 번에 한 응답 방식만 설정하고,
 * 마지막 요청 내용과 호출 횟수를 기록한다.
 */
final class FakeAnalyzer {

    private final HttpServer server;

    volatile String lastMethod;
    volatile String lastPath;
    volatile String lastToken;
    volatile String lastBody;
    volatile int callCount;

    private volatile int httpStatus = 200;
    private volatile String responseBody = "";
    private volatile long delayMillis;
    private volatile boolean drop;
    private volatile Runnable onRequest;

    private FakeAnalyzer(HttpServer server) {
        this.server = server;
    }

    static FakeAnalyzer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeAnalyzer analyzer = new FakeAnalyzer(server);
            server.createContext("/", analyzer::handle);
            // 타임아웃 테스트가 느린 응답을 붙들고 있어도 다른 요청을 받을 수 있게 스레드를 여럿 둔다
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return analyzer;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    void reset() {
        httpStatus = 200;
        responseBody = "";
        delayMillis = 0;
        drop = false;
        onRequest = null;
        callCount = 0;
        lastMethod = null;
        lastPath = null;
        lastToken = null;
        lastBody = null;
    }

    void replyWith(int status, String body) {
        this.httpStatus = status;
        this.responseBody = body;
    }

    void replyWithDelay(int status, String body, long millis) {
        replyWith(status, body);
        this.delayMillis = millis;
    }

    void dropConnection() {
        this.drop = true;
    }

    /** 요청을 받은 순간(응답하기 전)에 실행할 일. 그때 DB가 어떤 상태인지 확인할 때 쓴다. */
    void onRequest(Runnable action) {
        this.onRequest = action;
    }

    private void handle(HttpExchange exchange) throws IOException {
        callCount++;
        lastMethod = exchange.getRequestMethod();
        lastPath = exchange.getRequestURI().getPath();
        lastToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Runnable action = onRequest;
        if (action != null) {
            action.run();
        }
        if (drop) {
            exchange.close();
            return;
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(httpStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        } finally {
            exchange.close();
        }
    }
}
