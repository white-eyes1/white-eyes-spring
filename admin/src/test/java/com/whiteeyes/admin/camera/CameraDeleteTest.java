package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FNC-A-023 캠 삭제. 삭제는 분석 서버를 부르지 않으므로 가짜 분석 서버가 필요 없다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class CameraDeleteTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String DUMMY_URI = "dummy/videos/a.mp4";
    private static final String CAMERAS_URL = "/api/v1/manage/cameras";

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
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-023 가동한 적 없는 정지 캠을 삭제하면 200 {mode: HARD}이고 camera 행이 실제로 지워진다")
    void delete_neverStarted_hardDeleted() throws Exception {
        long cameraId = insertCamera("STOPPED", null);

        callDelete(cameraId, AdminTestLogin.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HARD"));

        assertThat(count("camera")).isZero();
    }

    @Test
    @DisplayName("FNC-A-023 가동 이력이 있는 정지 캠을 삭제하면 200 {mode: SOFT}이고 행은 남고 deleted_at만 채워진다")
    void delete_hasStartedBefore_softDeleted() throws Exception {
        long cameraId = insertCamera("STOPPED", "2026-10-01 09:00:00");

        callDelete(cameraId, AdminTestLogin.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SOFT"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM camera WHERE id = ?", cameraId);
        assertThat(row.get("DELETED_AT")).isNotNull();
        assertThat(row.get("FIRST_STARTED_AT")).as("가동 이력은 그대로").isNotNull();
    }

    @Test
    @DisplayName("FNC-A-023 삭제 표시한 캠의 지난 녹화는 그대로 남는다(녹화 기록이 camera를 가리킨다)")
    void delete_softDeleted_keepsRecordings() throws Exception {
        long cameraId = insertCamera("STOPPED", "2026-10-01 09:00:00");
        jdbcTemplate.update(
                "INSERT INTO recording (camera_id, started_at, ended_at, video_path, box_path) "
                        + "VALUES (?, '2026-10-01 09:00:00', '2026-10-01 09:01:00', 'v.mp4', 'b.json.gz')",
                cameraId);

        callDelete(cameraId, AdminTestLogin.admin()).andExpect(status().isOk());

        assertThat(count("recording")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-023 정지가 아닌 캠(PREPARING·RUNNING·ERROR)은 409 CAMERA_NOT_STOPPED이고 캠도 작업 기록도 그대로다")
    void delete_notStopped_conflict() throws Exception {
        for (String status : new String[] {"PREPARING", "RUNNING", "ERROR"}) {
            long cameraId = insertCamera(status, null);

            callDelete(cameraId, AdminTestLogin.admin())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CAMERA_NOT_STOPPED"));

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM camera WHERE id = ?", String.class, cameraId))
                    .as(status)
                    .isEqualTo(status);
            assertThat(count("work_history")).as(status).isZero();
            jdbcTemplate.update("DELETE FROM camera");
        }
    }

    @Test
    @DisplayName("FNC-A-023 STOPPED여도 가동 요청 상태(run_requested=1)이면 409 CAMERA_NOT_STOPPED이고 캠도 작업 기록도 그대로다")
    void delete_runRequested_conflict() throws Exception {
        long cameraId = insertCamera("STOPPED", "2026-10-01 09:00:00", true);

        callDelete(cameraId, AdminTestLogin.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMERA_NOT_STOPPED"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM camera WHERE id = ?", cameraId);
        assertThat(row.get("DELETED_AT")).isNull();
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(count("camera")).isEqualTo(1);
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-023 없는 캠이나 이미 삭제 표시된 캠이면 404다")
    void delete_unknownOrAlreadyDeleted_notFound() throws Exception {
        callDelete(999_999L, AdminTestLogin.admin()).andExpect(status().isNotFound());

        long cameraId = insertCamera("STOPPED", "2026-10-01 09:00:00");
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", cameraId);
        callDelete(cameraId, AdminTestLogin.admin()).andExpect(status().isNotFound());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-023 삭제하면 work_history에 CAMERA_CHANGE 1행(op: DELETE, mode)이 생긴다")
    void delete_recordsCameraChange() throws Exception {
        long hardId = insertCamera("STOPPED", null);
        callDelete(hardId, AdminTestLogin.admin()).andExpect(status().isOk());
        long softId = insertCamera("STOPPED", "2026-10-01 09:00:00");
        callDelete(softId, AdminTestLogin.admin()).andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("SELECT * FROM work_history ORDER BY id");
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("ACTION")).isEqualTo("CAMERA_CHANGE");
            assertThat(row.get("TARGET_TYPE")).isEqualTo("CAMERA");
            assertThat(row.get("TARGET_NAME")).isEqualTo("삼청동 캠");
            assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        }
        assertThat(((Number) rows.get(0).get("TARGET_ID")).longValue()).isEqualTo(hardId);
        assertThat(detail(rows.get(0)).get("op").asString()).isEqualTo("DELETE");
        assertThat(detail(rows.get(0)).get("mode").asString()).isEqualTo("HARD");
        assertThat(detail(rows.get(0)).get("zoneId").asLong()).isEqualTo(zoneId);
        assertThat(detail(rows.get(1)).get("mode").asString()).isEqualTo("SOFT");
    }

    @Test
    @DisplayName("FNC-C-026 로그인하지 않고 삭제하면 401이고 캠은 그대로다")
    void delete_notLoggedIn_unauthorized() throws Exception {
        long cameraId = insertCamera("STOPPED", null);

        callDelete(cameraId, null).andExpect(status().isUnauthorized());

        assertThat(count("camera")).isEqualTo(1);
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-023 작업 기록 저장이 실패하면 삭제도 취소된다(같은 트랜잭션)")
    void delete_historyFails_cameraStays() throws Exception {
        long cameraId = insertCamera("STOPPED", null);

        callDelete(cameraId, AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());

        assertThat(count("camera")).isEqualTo(1);
        assertThat(count("work_history")).isZero();
        long softId = insertSecondCameraAfterClear();
        callDelete(softId, AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM camera WHERE id = ?", Object.class, softId))
                .as("삭제 표시도 취소된다")
                .isNull();
    }

    @Test
    @DisplayName("FNC-A-023 삭제한 캠은 캠 목록에서 빠지고 그 구역에 새 캠을 등록할 수 있다")
    void delete_removesFromListAndFreesZone() throws Exception {
        long cameraId = insertCamera("STOPPED", "2026-10-01 09:00:00");
        mockMvc.perform(get(CAMERAS_URL).with(AdminTestLogin.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        callDelete(cameraId, AdminTestLogin.admin()).andExpect(status().isOk());

        mockMvc.perform(get(CAMERAS_URL).with(AdminTestLogin.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        String body = "{\"zoneId\":" + zoneId + ",\"name\":\"새 캠\",\"sourceType\":\"DUMMY\",\"sourceUri\":\"" + DUMMY_URI + "\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(CAMERAS_URL)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf())
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isCreated());
    }

    private ResultActions callDelete(long cameraId, RequestPostProcessor login) throws Exception {
        MockHttpServletRequestBuilder request = delete(CAMERAS_URL + "/{id}", cameraId).with(csrf());
        return mockMvc.perform(login == null ? request : request.with(login));
    }

    // H2는 JSON 칼럼을 바이트로 돌려준다
    private JsonNode detail(Map<String, Object> row) {
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        return detail;
    }

    // 구역당 캠 1대 규칙 때문에 한 번에 한 대만 만든다(테스트 안에서 반복할 때는 먼저 지운다)
    private long insertCamera(String status, String firstStartedAt) {
        return insertCamera(status, firstStartedAt, false);
    }

    private long insertCamera(String status, String firstStartedAt, boolean runRequested) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, first_started_at) "
                        + "VALUES (?, '삼청동 캠', 'DUMMY', ?, ?, ?, 0, ?)",
                zoneId,
                DUMMY_URI,
                runRequested,
                status,
                firstStartedAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private long insertSecondCameraAfterClear() {
        jdbcTemplate.update("DELETE FROM camera");
        return insertCamera("STOPPED", "2026-10-01 09:00:00");
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
