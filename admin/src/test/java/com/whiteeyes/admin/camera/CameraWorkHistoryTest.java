package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 캠 등록·가동·정지의 작업 기록 연결(FNC-A-018·020·021, FNC-C-026). 분석 서버는 가짜 HTTP 서버로 대신한다. */
// 운영 설정(open-in-view: false)과 같게 맞춘다. 가동·정지는 단계별로 따로 커밋해 요청 하나가 영속성 컨텍스트를 공유하면 검증이 틀어진다
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
class CameraWorkHistoryTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String DUMMY_URI = "dummy/videos/a.mp4";
    private static final String CAMERAS_URL = "/api/v1/manage/cameras";

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
        clearAll();
        regionLoadService.load(REGION_FILE);
        AdminTestLogin.loginForDirectCalls();
        zoneId = zoneRegisterService
                .register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE))
                .id();
        AdminTestLogin.clear();
        // 구역 등록의 작업 기록은 여기서 확인하지 않으므로 지우고 시작한다
        jdbcTemplate.update("DELETE FROM work_history");
    }

    // camera·zone·work_history를 남기면 다른 테스트가 region을 지우다 실패하거나 기록 수가 어긋난다
    @AfterEach
    void cleanUp() {
        AdminTestLogin.clear();
        clearAll();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM recording");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-018 캠을 등록하면 work_history에 CAMERA_CHANGE 1행(op: CREATE)이 생긴다")
    void register_recordsCameraChange() throws Exception {
        registerCamera(AdminTestLogin.admin()).andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        long cameraId = jdbcTemplate.queryForObject("SELECT id FROM camera", Long.class);
        assertActor(row);
        assertThat(row.get("ACTION")).isEqualTo("CAMERA_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("CAMERA");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(cameraId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("삼청동 캠");
        JsonNode detail = detail(row);
        assertThat(detail.get("op").asString()).isEqualTo("CREATE");
        assertThat(detail.get("zoneId").asLong()).isEqualTo(zoneId);
        assertThat(detail.get("sourceType").asString()).isEqualTo("DUMMY");
    }

    @Test
    @DisplayName("FNC-C-026 로그인하지 않고 캠을 등록하면 401이고 캠도 작업 기록도 생기지 않는다")
    void register_notLoggedIn_unauthorized() throws Exception {
        registerCamera(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(count("camera")).isZero();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 캠 등록도 취소된다(같은 트랜잭션)")
    void register_historyFails_cameraRolledBack() throws Exception {
        registerCamera(AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());

        assertThat(count("camera")).isZero();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-020 캠을 가동하면 work_history에 CAMERA_RUN 1행(op: START)이 생긴다")
    void start_recordsCameraRun() throws Exception {
        long cameraId = insertCamera("STOPPED", false);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        callCamera(cameraId, "start", AdminTestLogin.admin()).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertActor(row);
        assertThat(row.get("ACTION")).isEqualTo("CAMERA_RUN");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("CAMERA");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(cameraId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("삼청동 캠");
        assertThat(detail(row).get("op").asString()).isEqualTo("START");
    }

    @Test
    @DisplayName("FNC-A-020 가동에 실패하면(분석 서버 연결 실패) 작업 기록은 남지 않는다")
    void start_analyzerFails_noHistory() throws Exception {
        long cameraId = insertCamera("STOPPED", false);
        ANALYZER.dropConnection();

        callCamera(cameraId, "start", AdminTestLogin.admin()).andExpect(status().isBadGateway());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 로그인하지 않고 가동하면 401이고 분석 서버를 부르지 않으며 캠 상태도 그대로다")
    void start_notLoggedIn_unauthorized() throws Exception {
        long cameraId = insertCamera("STOPPED", false);

        callCamera(cameraId, "start", null).andExpect(status().isUnauthorized());

        assertThat(ANALYZER.callCount).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM camera WHERE id = ?", String.class, cameraId))
                .isEqualTo("STOPPED");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-020 기록 저장이 실패하면 가동 결과(상태 갱신)도 남지 않고 기록도 없다")
    void start_historyFails_noStateUpdate() throws Exception {
        long cameraId = insertCamera("STOPPED", false);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"PREPARING\"}");

        callCamera(cameraId, "start", AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());

        assertThat(count("work_history")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM camera WHERE id = ?", String.class, cameraId))
                .as("결과 기록(3단계)이 한 트랜잭션이라 같이 취소된다")
                .isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("FNC-A-021 캠을 정지하면 work_history에 CAMERA_RUN 1행(op: STOP)이 생긴다")
    void stop_recordsCameraRun() throws Exception {
        long cameraId = insertCamera("RUNNING", true);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"STOPPED\"}");

        callCamera(cameraId, "stop", AdminTestLogin.admin()).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertActor(row);
        assertThat(row.get("ACTION")).isEqualTo("CAMERA_RUN");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("CAMERA");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(cameraId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("삼청동 캠");
        assertThat(detail(row).get("op").asString()).isEqualTo("STOP");
    }

    @Test
    @DisplayName("FNC-A-021 분석 서버에 전달하지 못해도 앱 쪽 정지는 성공이므로 작업 기록이 남는다")
    void stop_deliveryFails_stillRecorded() throws Exception {
        long cameraId = insertCamera("RUNNING", true);
        ANALYZER.dropConnection();

        callCamera(cameraId, "stop", AdminTestLogin.admin()).andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT action FROM work_history");
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("FNC-C-026 로그인하지 않고 정지하면 401이고 분석 서버를 부르지 않으며 캠 상태도 그대로다")
    void stop_notLoggedIn_unauthorized() throws Exception {
        long cameraId = insertCamera("RUNNING", true);

        callCamera(cameraId, "stop", null).andExpect(status().isUnauthorized());

        assertThat(ANALYZER.callCount).isZero();
        Map<String, Object> camera = jdbcTemplate.queryForMap("SELECT status, run_requested FROM camera WHERE id = ?", cameraId);
        assertThat(camera.get("STATUS")).isEqualTo("RUNNING");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-021 기록 저장이 실패하면 정지 결과(STOPPED)도 남지 않는다")
    void stop_historyFails_noStateUpdate() throws Exception {
        long cameraId = insertCamera("RUNNING", true);
        ANALYZER.replyWith(200, "{\"cameraId\":" + cameraId + ",\"status\":\"STOPPED\"}");

        callCamera(cameraId, "stop", AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());

        assertThat(count("work_history")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM camera WHERE id = ?", String.class, cameraId))
                .isEqualTo("RUNNING");
    }

    private void assertActor(Map<String, Object> row) {
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
    }

    // H2는 JSON 칼럼을 바이트로 돌려준다
    private JsonNode detail(Map<String, Object> row) {
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        return detail;
    }

    private ResultActions registerCamera(RequestPostProcessor login) throws Exception {
        String body = "{\"zoneId\":" + zoneId + ",\"name\":\"삼청동 캠\",\"sourceType\":\"DUMMY\",\"sourceUri\":\"" + DUMMY_URI + "\"}";
        return perform(post(CAMERAS_URL).contentType(MediaType.APPLICATION_JSON).content(body), login);
    }

    private ResultActions callCamera(long cameraId, String action, RequestPostProcessor login) throws Exception {
        return perform(post(CAMERAS_URL + "/{id}/" + action, cameraId), login);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor login) throws Exception {
        MockHttpServletRequestBuilder withCsrf = request.with(csrf());
        return mockMvc.perform(login == null ? withCsrf : withCsrf.with(login));
    }

    private long insertCamera(String status, boolean runRequested) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress) "
                        + "VALUES (?, '삼청동 캠', 'DUMMY', ?, ?, ?, 0)",
                zoneId,
                DUMMY_URI,
                runRequested,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
