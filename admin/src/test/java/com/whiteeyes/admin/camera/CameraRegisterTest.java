package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CameraRegisterTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String CAMERAS_URL = "/api/v1/manage/cameras";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";
    private static final String DUMMY_URI = "dummy/videos/a.mp4";

    // 삼청동 안의 사각형 / 소공동 안의 사각형
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long samcheongZoneId;
    private long sogongZoneId;

    @BeforeEach
    void prepareZones() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        samcheongZoneId = createZone("삼청동 1구역", SAMCHEONG_SQUARE);
        sogongZoneId = createZone("소공동 1구역", SOGONG_SQUARE);
    }

    // camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearCamerasAndZones() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-018 LIVE 캠을 등록하면 201과 캠 정보(정지 상태)를 돌려주고 camera에 저장한다")
    void register_liveCamera_createsStoppedCamera() throws Exception {
        register(samcheongZoneId, "삼청동 캠", "LIVE", LIVE_URI)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.zoneId").value(samcheongZoneId))
                .andExpect(jsonPath("$.name").value("삼청동 캠"))
                .andExpect(jsonPath("$.sourceType").value("LIVE"))
                .andExpect(jsonPath("$.sourceUri").value(LIVE_URI))
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.progress").value(0))
                .andExpect(jsonPath("$.runRequested").value(false));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM camera");
        assertThat(row.get("ZONE_ID")).isEqualTo(samcheongZoneId);
        assertThat(row.get("NAME")).isEqualTo("삼청동 캠");
        assertThat(row.get("SOURCE_TYPE")).isEqualTo("LIVE");
        assertThat(row.get("SOURCE_URI")).isEqualTo(LIVE_URI);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(((Number) row.get("RUN_REQUESTED")).intValue()).isZero();
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
        assertThat(row.get("ERROR_MESSAGE")).isNull();
        assertThat(row.get("FIRST_STARTED_AT")).isNull();
        assertThat(row.get("DELETED_AT")).isNull();
        assertThat(row.get("CREATED_AT")).isNotNull();
        assertThat(row.get("UPDATED_AT")).isNotNull();
    }

    @Test
    @DisplayName("FNC-A-018 DUMMY 캠도 등록할 수 있다")
    void register_dummyCamera_created() throws Exception {
        register(samcheongZoneId, "삼청동 더미 캠", "DUMMY", DUMMY_URI)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("DUMMY"))
                .andExpect(jsonPath("$.sourceUri").value(DUMMY_URI))
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    @Test
    @DisplayName("FNC-A-018 이름·영상 주소 앞뒤 공백은 지우고 저장한다")
    void register_trimsNameAndUri() throws Exception {
        register(samcheongZoneId, "  삼청동 캠  ", "LIVE", "  " + LIVE_URI + "  ").andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, source_uri FROM camera");
        assertThat(row.get("NAME")).isEqualTo("삼청동 캠");
        assertThat(row.get("SOURCE_URI")).isEqualTo(LIVE_URI);
    }

    @Test
    @DisplayName("FNC-A-018 없는 구역이면 404 ZONE_NOT_FOUND")
    void register_zoneNotFound() throws Exception {
        register(999_999L, "캠", "LIVE", LIVE_URI)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 삭제된 구역이면 404 ZONE_NOT_FOUND")
    void register_deletedZone() throws Exception {
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", samcheongZoneId);

        register(samcheongZoneId, "캠", "LIVE", LIVE_URI)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 그 구역에 삭제되지 않은 캠이 있으면 409 ZONE_HAS_CAMERA")
    void register_zoneAlreadyHasCamera() throws Exception {
        register(samcheongZoneId, "첫 캠", "LIVE", LIVE_URI).andExpect(status().isCreated());

        register(samcheongZoneId, "둘째 캠", "DUMMY", DUMMY_URI)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ZONE_HAS_CAMERA"));

        assertThat(cameraCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-018 다른 구역에는 각각 캠을 등록할 수 있다")
    void register_otherZone_allowed() throws Exception {
        register(samcheongZoneId, "삼청동 캠", "LIVE", LIVE_URI).andExpect(status().isCreated());

        register(sogongZoneId, "소공동 캠", "LIVE", LIVE_URI).andExpect(status().isCreated());

        assertThat(cameraCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-018 캠이 삭제됐으면 같은 구역에 다시 등록할 수 있다")
    void register_afterCameraDeleted_allowed() throws Exception {
        register(samcheongZoneId, "첫 캠", "LIVE", LIVE_URI).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP");

        register(samcheongZoneId, "새 캠", "LIVE", LIVE_URI).andExpect(status().isCreated());

        assertThat(cameraCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-018 LIVE 주소가 rtsp:// 로 시작하지 않으면 400이고 저장하지 않는다")
    void register_liveUriNotRtsp_badRequest() throws Exception {
        register(samcheongZoneId, "캠", "LIVE", "http://192.168.0.10/stream")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 DUMMY 주소가 규칙(dummy/videos/ 시작, .mp4 끝, '..' 없음)을 어기면 400이고 저장하지 않는다")
    void register_dummyUriBreaksRule_badRequest() throws Exception {
        register(samcheongZoneId, "캠", "DUMMY", "dummy/videos/../secret.mp4")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 처리 순서대로 구역 확인이 영상 주소 검사보다 먼저다")
    void register_zoneCheckedBeforeUriRule() throws Exception {
        register(999_999L, "캠", "LIVE", "http://wrong")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-018 이름이 비어 있으면 400 INVALID_INPUT과 fieldErrors")
    void register_blankName_badRequest() throws Exception {
        register(samcheongZoneId, "   ", "LIVE", LIVE_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 이름이 50자를 넘으면 400 INVALID_INPUT")
    void register_nameTooLong_badRequest() throws Exception {
        register(samcheongZoneId, "가".repeat(51), "LIVE", LIVE_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 영상 주소가 255자를 넘으면 400 INVALID_INPUT")
    void register_uriTooLong_badRequest() throws Exception {
        register(samcheongZoneId, "캠", "LIVE", "rtsp://" + "a".repeat(250))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 sourceType이 없거나 LIVE/DUMMY가 아니면 400 INVALID_INPUT")
    void register_invalidSourceType_badRequest() throws Exception {
        mockMvc.perform(post(CAMERAS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zoneId\":" + samcheongZoneId + ",\"name\":\"캠\",\"sourceUri\":\"" + LIVE_URI + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sourceType"));
        register(samcheongZoneId, "캠", "WEBCAM", LIVE_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(cameraCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-018 zoneId가 없으면 400 INVALID_INPUT")
    void register_missingZoneId_badRequest() throws Exception {
        mockMvc.perform(post(CAMERAS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"캠\",\"sourceType\":\"LIVE\",\"sourceUri\":\"" + LIVE_URI + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("zoneId"));

        assertThat(cameraCount()).isZero();
    }

    private long createZone(String name, String polygon) {
        return zoneRegisterService.register(name, jsonMapper.readTree(polygon)).id();
    }

    private ResultActions register(long zoneId, String name, String sourceType, String sourceUri) throws Exception {
        String body = "{\"zoneId\":" + zoneId + ",\"name\":\"" + name + "\",\"sourceType\":\"" + sourceType
                + "\",\"sourceUri\":\"" + sourceUri + "\"}";
        return mockMvc.perform(post(CAMERAS_URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long cameraCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM camera", Long.class);
    }
}
