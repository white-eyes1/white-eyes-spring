package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

/** FNC-A-021 캠 정지. 분석 서버는 같은 JVM에 띄운 가짜 HTTP 서버로 대신한다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class CameraStopTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String DUMMY_URI = "dummy/videos/a.mp4";
    private static final String TEST_TOKEN = "test-internal-token";
    private static final String DELIVERY_FAILED = "분석 서버에 전달 실패";

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
    @DisplayName("FNC-A-021 가동 중인 캠을 정지하면 200 {status: STOPPED, warning: null}이고 STOPPED·progress=0·run_requested=0이 된다")
    void stop_runningCamera_becomesStopped() throws Exception {
        long cameraId = insertCamera("RUNNING", 0, null, true);

        stop(cameraId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$", hasKey("warning")))
                .andExpect(jsonPath("$.warning").value(nullValue()));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
        assertThat(isRunRequested(row)).isFalse();
    }

    @Test
    @DisplayName("분석 서버로 보내는 요청: POST, 경로의 캠 번호, X-Internal-Token")
    void stop_sendsAnalyzerRequest() throws Exception {
        long cameraId = insertCamera("RUNNING", 0, null, true);

        stop(cameraId).andExpect(status().isOk());

        assertThat(ANALYZER.callCount).isEqualTo(1);
        assertThat(ANALYZER.lastMethod).isEqualTo("POST");
        assertThat(ANALYZER.lastPath).isEqualTo("/analyzer/v1/cameras/" + cameraId + "/stop");
        assertThat(ANALYZER.lastToken).isEqualTo(TEST_TOKEN);
    }

    @Test
    @DisplayName("완료 기준: 준비 중(PREPARING, 진행률 40) 캠을 정지하면 분석 서버에 정지를 지시하고 STOPPED·progress=0이 된다")
    void stop_preparingCamera_cancelsPreparation() throws Exception {
        long cameraId = insertCamera("PREPARING", 40, null, true);

        stop(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));

        assertThat(ANALYZER.callCount).as("분석 서버가 준비를 취소하도록 정지를 지시한다").isEqualTo(1);
        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
        assertThat(isRunRequested(row)).isFalse();
    }

    @Test
    @DisplayName("ERROR 캠을 정지하면 오류 내용이 지워지고 STOPPED가 된다")
    void stop_errorCamera_clearsErrorMessage() throws Exception {
        long cameraId = insertCamera("ERROR", 40, "분석 서버 연결 실패", true);

        stop(cameraId).andExpect(status().isOk());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(row.get("ERROR_MESSAGE")).isNull();
        assertThat(isRunRequested(row)).isFalse();
    }

    @Test
    @DisplayName("이미 정지된 캠을 다시 정지해도 200이고 분석 서버에도 정지를 지시한다(멱등)")
    void stop_alreadyStopped_idempotent() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false);

        stop(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));

        assertThat(ANALYZER.callCount).isEqualTo(1);
        assertThat(camera(cameraId).get("STATUS")).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("run_requested=0은 분석 서버를 부르기 전에 이미 확정돼 있다(처리 순서 1이 2보다 먼저)")
    void stop_cancelsRunRequestBeforeCallingAnalyzer() throws Exception {
        long cameraId = insertCamera("RUNNING", 0, null, true);
        AtomicReference<Boolean> runRequestedWhileCalling = new AtomicReference<>();
        ANALYZER.onRequest(() -> runRequestedWhileCalling.set(isRunRequested(camera(cameraId))));

        stop(cameraId).andExpect(status().isOk());

        assertThat(runRequestedWhileCalling.get()).isFalse();
    }

    @Test
    @DisplayName("분석 서버 연결이 끊겨도 200이고 warning='분석 서버에 전달 실패', 앱 쪽은 STOPPED·run_requested=0으로 저장한다")
    void stop_analyzerConnectionFails_stillStoppedWithWarning() throws Exception {
        long cameraId = insertCamera("RUNNING", 0, null, true);
        ANALYZER.dropConnection();

        stop(cameraId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.warning").value(DELIVERY_FAILED));

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
        assertThat(isRunRequested(row)).isFalse();
    }

    @Test
    @DisplayName("분석 서버가 오류 응답(500·401·404)으로 답해도 200과 같은 경고, 앱 쪽은 STOPPED")
    void stop_analyzerErrorResponses_stillStoppedWithWarning() throws Exception {
        for (int http : new int[] {500, 401, 404}) {
            long cameraId = insertCamera("PREPARING", 30, null, true);
            ANALYZER.replyWith(http, "{\"code\":\"X\"}");

            stop(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.warning").value(DELIVERY_FAILED));

            Map<String, Object> row = camera(cameraId);
            assertThat(row.get("STATUS")).as("http " + http).isEqualTo("STOPPED");
            assertThat(isRunRequested(row)).as("http " + http).isFalse();
            jdbcTemplate.update("DELETE FROM camera");
        }
    }

    @Test
    @DisplayName("분석 서버가 15초 안에 답하지 않으면 경고와 함께 200이고 앱 쪽은 STOPPED다(정지 타임아웃 15초)")
    void stop_analyzerTimesOut_stillStoppedWithWarning() throws Exception {
        long cameraId = insertCamera("RUNNING", 0, null, true);
        ANALYZER.replyWithDelay(200, "", 17_000);

        long begin = System.currentTimeMillis();
        stop(cameraId).andExpect(status().isOk()).andExpect(jsonPath("$.warning").value(DELIVERY_FAILED));
        long elapsed = System.currentTimeMillis() - begin;

        assertThat(elapsed).isBetween(14_500L, 16_800L);
        assertThat(camera(cameraId).get("STATUS")).isEqualTo("STOPPED");
        assertThat(isRunRequested(camera(cameraId))).isFalse();
    }

    @Test
    @DisplayName("없는 캠이나 삭제된 캠이면 404이고 분석 서버를 부르지 않는다")
    void stop_unknownOrDeletedCamera_notFound() throws Exception {
        stop(999_999L).andExpect(status().isNotFound());

        long deletedId = insertCamera("RUNNING", 0, null, true);
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedId);
        stop(deletedId).andExpect(status().isNotFound());

        assertThat(ANALYZER.callCount).isZero();
        assertThat(isRunRequested(camera(deletedId))).as("삭제된 캠은 건드리지 않는다").isTrue();
    }

    private ResultActions stop(long cameraId) throws Exception {
        return mockMvc.perform(post("/api/v1/manage/cameras/{id}/stop", cameraId));
    }

    // 구역당 캠 1대 규칙 때문에 한 번에 한 대만 만든다(테스트 안에서 반복할 때는 먼저 지운다)
    private long insertCamera(String status, int progress, String errorMessage, boolean runRequested) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, error_message) "
                        + "VALUES (?, ?, 'DUMMY', ?, ?, ?, ?, ?)",
                zoneId,
                "삼청동 캠",
                DUMMY_URI,
                runRequested,
                status,
                progress,
                errorMessage);
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
