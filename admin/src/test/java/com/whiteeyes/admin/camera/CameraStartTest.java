package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** FNC-A-020 캠 가동. 분석 서버는 같은 JVM에 띄운 가짜 HTTP 서버로 대신한다. */
@SpringBootTest
@AutoConfigureMockMvc
class CameraStartTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String DUMMY_URI = "dummy/videos/a.mp4";
    private static final String TEST_TOKEN = "test-internal-token";

    private static final FakeAnalyzer ANALYZER = FakeAnalyzer.start();

    @DynamicPropertySource
    static void analyzerProperties(DynamicPropertyRegistry registry) {
        registry.add("analyzer.base-url", ANALYZER::baseUrl);
    }

    @AfterAll
    static void stopAnalyzer() {
        ANALYZER.stop();
    }

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long zoneId;

    @BeforeEach
    void prepareZone() {
        ANALYZER.reset();
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        zoneId = zoneRegisterService
                .register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE))
                .id();
    }

    // camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearCamerasAndZones() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-020 정지 상태 더미 캠을 가동하면 분석 서버가 PREPARING으로 답하고 캠이 PREPARING·run_requested=1이 된다")
    void start_stoppedDummyCamera_becomesPreparing() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        start(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARING"));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("PREPARING");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
        assertThat(row.get("ERROR_MESSAGE")).isNull();
        assertThat(isRunRequested(row)).isTrue();
        assertThat(row.get("FIRST_STARTED_AT")).isNotNull();
    }

    @Test
    @DisplayName("분석 서버로 보내는 요청: 경로의 캠 번호, X-Internal-Token, 본문 sourceType·sourceUri")
    void start_sendsAnalyzerRequest() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        start(cameraId).andExpect(status().isOk());

        assertThat(ANALYZER.lastMethod).isEqualTo("POST");
        assertThat(ANALYZER.lastPath).isEqualTo("/analyzer/v1/cameras/" + cameraId + "/start");
        assertThat(ANALYZER.lastToken).isEqualTo(TEST_TOKEN);
        assertThat(jsonMapper.readTree(ANALYZER.lastBody).path("sourceType").asString(""))
                .isEqualTo("DUMMY");
        assertThat(jsonMapper.readTree(ANALYZER.lastBody).path("sourceUri").asString(""))
                .isEqualTo(DUMMY_URI);
    }

    @Test
    @DisplayName("분석 서버가 이미 돌고 있어 RUNNING으로 답하면 캠이 RUNNING이 된다")
    void start_analyzerReportsRunning_becomesRunning() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"RUNNING\"}");

        start(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));

        assertThat(camera(cameraId).get("STATUS")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("ERROR 상태 캠을 다시 가동하면 성공해서 오류 내용이 지워지고 PREPARING이 된다")
    void start_errorCamera_clearsErrorMessage() throws Exception {
        long cameraId = insertCamera("ERROR", 40, "영상 파일·스트림 없음", true, null);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        start(cameraId).andExpect(status().isOk());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("PREPARING");
        assertThat(row.get("ERROR_MESSAGE")).isNull();
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
    }

    @Test
    @DisplayName("이미 first_started_at이 있으면 다시 가동해도 그 값을 바꾸지 않는다")
    void start_keepsFirstStartedAt() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, "2026-01-02 03:04:05");
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        start(cameraId).andExpect(status().isOk());

        assertThat(camera(cameraId).get("FIRST_STARTED_AT").toString()).startsWith("2026-01-02 03:04:05");
    }

    @Test
    @DisplayName("STOPPED·ERROR가 아니면(PREPARING·RUNNING) 409 INVALID_STATUS이고 분석 서버를 부르지 않는다")
    void start_notStartable_conflict() throws Exception {
        for (String state : new String[] {"PREPARING", "RUNNING"}) {
            ANALYZER.reset();
            long cameraId = insertCamera(state, 10, null, true, null);
            jdbcTemplate.update("UPDATE camera SET run_requested = 0 WHERE id = ?", cameraId);

            start(cameraId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));

            assertThat(ANALYZER.callCount).as(state).isZero();
            assertThat(camera(cameraId).get("STATUS")).isEqualTo(state);
            assertThat(isRunRequested(camera(cameraId))).as(state + " run_requested 유지").isFalse();
            jdbcTemplate.update("DELETE FROM camera");
        }
    }

    @Test
    @DisplayName("분석 서버 연결이 끊기면 502 ANALYZER_UNAVAILABLE, status=ERROR·'분석 서버 연결 실패', run_requested=1 유지, first_started_at 비어 있음")
    void start_analyzerConnectionFails_badGatewayAndError() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.dropConnection();

        start(cameraId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ANALYZER_UNAVAILABLE"));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("ERROR");
        assertThat(row.get("ERROR_MESSAGE")).isEqualTo("분석 서버 연결 실패");
        assertThat(isRunRequested(row)).isTrue();
        assertThat(row.get("FIRST_STARTED_AT")).isNull();
    }

    @Test
    @DisplayName("분석 서버가 5초 안에 답하지 않으면 502 ANALYZER_UNAVAILABLE이고 캠은 ERROR가 된다")
    void start_analyzerTimesOut_badGateway() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWithDelay(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}", 7_000);

        long begin = System.currentTimeMillis();
        start(cameraId).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("ANALYZER_UNAVAILABLE"));
        long elapsed = System.currentTimeMillis() - begin;

        assertThat(elapsed).isBetween(4_500L, 6_800L);
        assertThat(camera(cameraId).get("STATUS")).isEqualTo("ERROR");
        assertThat(camera(cameraId).get("ERROR_MESSAGE")).isEqualTo("분석 서버 연결 실패");
    }

    @Test
    @DisplayName("분석 서버가 400 SOURCE_NOT_FOUND로 답하면 422 SOURCE_NOT_FOUND, status=ERROR·'영상 파일·스트림 없음', run_requested=1 유지")
    void start_sourceNotFound_unprocessableAndError() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(400, "{\"code\":\"SOURCE_NOT_FOUND\",\"message\":\"영상 파일이 없습니다.\"}");

        start(cameraId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("ERROR");
        assertThat(row.get("ERROR_MESSAGE")).isEqualTo("영상 파일·스트림 없음");
        assertThat(isRunRequested(row)).isTrue();
        assertThat(row.get("FIRST_STARTED_AT")).isNull();
    }

    @Test
    @DisplayName("분석 서버가 그 밖의 오류(500·401·501·400 다른 코드)로 답하면 502 ANALYZER_UNAVAILABLE이고 캠은 ERROR가 된다")
    void start_otherAnalyzerErrors_badGateway() throws Exception {
        record Case(int http, String body) {}
        Case[] cases = {
            new Case(500, "{\"code\":\"INTERNAL_ERROR\"}"),
            new Case(401, "{\"code\":\"UNAUTHORIZED\"}"),
            new Case(501, "{\"code\":\"SOURCE_TYPE_NOT_SUPPORTED\"}"),
            new Case(400, "{\"code\":\"INVALID_INPUT\"}"),
            new Case(500, "<html>Bad Gateway</html>")
        };
        for (Case c : cases) {
            long cameraId = insertCamera("STOPPED", 0, null, false, null);
            ANALYZER.replyWith(c.http(), c.body());

            start(cameraId)
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("ANALYZER_UNAVAILABLE"));

            assertThat(camera(cameraId).get("STATUS")).as(c.toString()).isEqualTo("ERROR");
            jdbcTemplate.update("DELETE FROM camera");
        }
    }

    @Test
    @DisplayName("분석 서버가 200인데 알 수 없는 상태나 빈 본문으로 답하면 502 ANALYZER_UNAVAILABLE이고 캠은 ERROR가 된다")
    void start_unexpectedSuccessBody_badGateway() throws Exception {
        for (String body : new String[] {"{\"cameraId\":1,\"status\":\"STOPPED\"}", "", "not json"}) {
            long cameraId = insertCamera("STOPPED", 0, null, false, null);
            ANALYZER.replyWith(200, body);

            start(cameraId).andExpect(status().isBadGateway());

            assertThat(camera(cameraId).get("STATUS")).as(body).isEqualTo("ERROR");
            jdbcTemplate.update("DELETE FROM camera");
        }
    }

    @Test
    @DisplayName("없는 캠이나 삭제된 캠이면 404이고 분석 서버를 부르지 않는다")
    void start_unknownOrDeletedCamera_notFound() throws Exception {
        start(999_999L).andExpect(status().isNotFound());

        long deletedId = insertCamera("STOPPED", 0, null, false, null);
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedId);
        start(deletedId).andExpect(status().isNotFound());

        assertThat(ANALYZER.callCount).isZero();
    }

    private ResultActions start(long cameraId) throws Exception {
        return mockMvc.perform(post("/api/v1/manage/cameras/{id}/start", cameraId));
    }

    // 구역당 캠 1대 규칙 때문에 한 번에 한 대만 만든다(테스트 안에서 반복할 때는 먼저 지운다)
    private long insertCamera(String status, int progress, String errorMessage, boolean runRequested, String firstStartedAt) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, error_message, first_started_at) "
                        + "VALUES (?, ?, 'DUMMY', ?, ?, ?, ?, ?, ?)",
                zoneId,
                "삼청동 캠",
                DUMMY_URI,
                runRequested,
                status,
                progress,
                errorMessage,
                firstStartedAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private Map<String, Object> camera(long cameraId) {
        return jdbcTemplate.queryForMap("SELECT * FROM camera WHERE id = ?", cameraId);
    }

    // H2는 TINYINT(1)을 숫자 또는 boolean으로 돌려줄 수 있어 둘 다 받는다
    private boolean isRunRequested(Map<String, Object> row) {
        Object value = row.get("RUN_REQUESTED");
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    /** 분석 서버 역할의 가짜 HTTP 서버. 한 번에 한 응답 방식만 설정하고, 마지막 요청 내용을 기록한다. */
    private static final class FakeAnalyzer {

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

        private void handle(HttpExchange exchange) throws IOException {
            callCount++;
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getPath();
            lastToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
}
