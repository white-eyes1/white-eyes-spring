package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** FNC-A-020 캠 가동. 분석 서버는 같은 JVM에 띄운 가짜 HTTP 서버로 대신한다. */
// 운영 설정(open-in-view: false)과 같게 맞춘다. 켜 두면 요청 하나가 영속성 컨텍스트를 공유해 단계별 커밋을 검증할 수 없다
@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
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
        AdminTestLogin.loginForDirectCalls();
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
        AdminTestLogin.clear();
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
    @DisplayName("분석 서버 응답을 기다리는 동안 정지가 끝나면(STOPPED·run_requested=0) 응답이 와도 PREPARING으로 덮어쓰지 않고 first_started_at도 남기지 않는다")
    void start_stoppedWhileWaiting_doesNotOverwrite() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWithDelay(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}", 1_000);

        Thread stopper = new Thread(() -> {
            // 분석 서버가 요청을 받은 뒤(= 가동 요청 기록이 끝난 뒤)에 정지가 끼어들게 한다
            long deadline = System.currentTimeMillis() + 10_000;
            while (ANALYZER.callCount == 0 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            jdbcTemplate.update("UPDATE camera SET status = 'STOPPED', run_requested = 0, progress = 0 WHERE id = ?", cameraId);
        });
        stopper.start();
        start(cameraId).andExpect(status().isOk());
        stopper.join();

        assertThat(camera(cameraId).get("STATUS")).isEqualTo("STOPPED");
        assertThat(isRunRequested(camera(cameraId))).isFalse();
        assertThat(camera(cameraId).get("FIRST_STARTED_AT")).isNull();
    }

    @Test
    @DisplayName("first_started_at은 한국 시각(KST)으로 기록된다")
    void start_firstStartedAtIsKst() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        java.time.LocalDateTime before = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).minusSeconds(5);
        start(cameraId).andExpect(status().isOk());
        java.time.LocalDateTime after = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).plusSeconds(5);

        java.time.LocalDateTime saved = ((java.sql.Timestamp) camera(cameraId).get("FIRST_STARTED_AT")).toLocalDateTime();
        assertThat(saved).isBetween(before, after);
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
    @DisplayName("FNC-A-020 분석 서버가 409 CAMERA_STOPPING으로 답하면 409 CAMERA_STOPPING이고 캠은 요청 전 그대로(STOPPED·run_requested=0), 첫 가동·작업 기록도 없다")
    void start_cameraStopping_revertsToBefore() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(409, "{\"code\":\"CAMERA_STOPPING\",\"message\":\"정지 중\"}");

        start(cameraId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMERA_STOPPING"))
                .andExpect(jsonPath("$.message").value("이전 정지가 아직 끝나지 않았습니다. 잠시 뒤 다시 시작해 주세요."));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(row.get("ERROR_MESSAGE")).isNull();
        assertThat(isRunRequested(row)).isFalse();
        assertThat(row.get("FIRST_STARTED_AT")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM work_history WHERE action = 'CAMERA_RUN' AND target_id = ?",
                        Long.class,
                        cameraId))
                .isZero();
    }

    @Test
    @DisplayName("FNC-A-020 ERROR 캠이 409 CAMERA_STOPPING을 받으면 ERROR·오류 내용·run_requested=1이 그대로 남는다")
    void start_cameraStopping_errorCameraKeepsItsState() throws Exception {
        long cameraId = insertCamera("ERROR", 0, "분석 서버 연결 실패", true, null);
        ANALYZER.replyWith(409, "{\"code\":\"CAMERA_STOPPING\"}");

        start(cameraId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMERA_STOPPING"));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("ERROR");
        assertThat(row.get("ERROR_MESSAGE")).isEqualTo("분석 서버 연결 실패");
        assertThat(isRunRequested(row)).isTrue();
    }

    @Test
    @DisplayName("FNC-A-020 409 CAMERA_STOPPING 뒤에 정지가 끝나 다시 시작하면 정상적으로 가동된다")
    void start_afterCameraStopping_retrySucceeds() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false, null);
        ANALYZER.replyWith(409, "{\"code\":\"CAMERA_STOPPING\"}");
        start(cameraId).andExpect(status().isConflict());

        ANALYZER.replyWith(200, "{\"status\":\"PREPARING\"}");
        start(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARING"));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("PREPARING");
        assertThat(isRunRequested(row)).isTrue();
        assertThat(row.get("FIRST_STARTED_AT")).isNotNull();
    }

    @Test
    @DisplayName("분석 서버가 그 밖의 오류(500·401·501·400·409 다른 코드)로 답하면 502 ANALYZER_UNAVAILABLE이고 캠은 ERROR가 된다")
    void start_otherAnalyzerErrors_badGateway() throws Exception {
        record Case(int http, String body) {}
        Case[] cases = {
            new Case(500, "{\"code\":\"INTERNAL_ERROR\"}"),
            new Case(401, "{\"code\":\"UNAUTHORIZED\"}"),
            new Case(501, "{\"code\":\"SOURCE_TYPE_NOT_SUPPORTED\"}"),
            new Case(400, "{\"code\":\"INVALID_INPUT\"}"),
            new Case(409, "{\"code\":\"INVALID_STATUS\"}"),
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
        return mockMvc.perform(post("/api/v1/manage/cameras/{id}/start", cameraId).with(AdminTestLogin.admin()));
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
}
