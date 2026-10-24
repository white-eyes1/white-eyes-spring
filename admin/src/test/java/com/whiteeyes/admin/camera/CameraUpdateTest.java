package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 캠 정보 수정(FNC-A-019). 정지 상태인 캠의 이름·입력 방식·영상 주소를 고친다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class CameraUpdateTest {

    private static final String CAMERAS_URL = "/api/v1/manage/cameras/";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";
    private static final String DUMMY_URI = "dummy/videos/a.mp4";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private long zoneId;
    private long cameraId;

    @BeforeEach
    void prepare() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999993', '캠 수정 테스트동', '{}', 37.600000, 127.002000)");
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999993'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, '캠 수정 1구역', 100.00, '{}' FORMAT JSON, 37.600000, 127.002000)",
                dongId);
        zoneId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
        cameraId = insertCamera("STOPPED", false, null);
    }

    // work_history·camera·zone·region을 남기면 뒤에 도는 다른 테스트가 지우다 FK로 실패하거나 기록 수가 어긋난다
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("DELETE FROM region WHERE code = '99999993'");
    }

    @Test
    @DisplayName("FNC-A-019 정지 상태 캠의 이름·입력 방식·영상 주소를 함께 고치면 200과 고친 캠 정보를 돌려준다")
    void update_allFields() throws Exception {
        update(cameraId, "{\"name\":\"고친 캠\",\"sourceType\":\"DUMMY\",\"sourceUri\":\"" + DUMMY_URI + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cameraId))
                .andExpect(jsonPath("$.name").value("고친 캠"))
                .andExpect(jsonPath("$.sourceType").value("DUMMY"))
                .andExpect(jsonPath("$.sourceUri").value(DUMMY_URI))
                .andExpect(jsonPath("$.status").value("STOPPED"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, source_type, source_uri, status FROM camera WHERE id = ?", cameraId);
        assertThat(row.get("NAME")).isEqualTo("고친 캠");
        assertThat(row.get("SOURCE_TYPE")).isEqualTo("DUMMY");
        assertThat(row.get("SOURCE_URI")).isEqualTo(DUMMY_URI);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("FNC-A-019 보낸 필드만 바뀐다(이름만, 영상 주소만)하고 이름 앞뒤 공백은 지운다")
    void update_onlySentFields() throws Exception {
        update(cameraId, "{\"name\":\"  이름만  \"}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("이름만"));
        assertThat(jdbcTemplate.queryForObject("SELECT source_uri FROM camera WHERE id = ?", String.class, cameraId))
                .isEqualTo(LIVE_URI);

        String other = "rtsp://192.168.0.20:554/stream2";
        update(cameraId, "{\"sourceUri\":\"" + other + "\"}").andExpect(status().isOk());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, source_type, source_uri FROM camera WHERE id = ?", cameraId);
        assertThat(row.get("NAME")).isEqualTo("이름만");
        assertThat(row.get("SOURCE_TYPE")).isEqualTo("LIVE");
        assertThat(row.get("SOURCE_URI")).isEqualTo(other);
    }

    @Test
    @DisplayName("FNC-A-019 영상 주소는 바뀐 뒤의 입력 방식 규칙으로 검사한다(LIVE→DUMMY인데 rtsp 주소면 400, 저장 안 함)")
    void update_validatesAgainstNewSourceType() throws Exception {
        update(cameraId, "{\"sourceType\":\"DUMMY\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        update(cameraId, "{\"sourceUri\":\"http://example.com/stream\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        update(cameraId, "{\"sourceType\":\"DUMMY\",\"sourceUri\":\"dummy/videos/../secret.mp4\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT source_type, source_uri FROM camera WHERE id = ?", cameraId);
        assertThat(row.get("SOURCE_TYPE")).isEqualTo("LIVE");
        assertThat(row.get("SOURCE_URI")).isEqualTo(LIVE_URI);
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-019 준비 중·가동 중·오류 상태이거나 가동 요청이 남아 있으면 409 CAMERA_NOT_STOPPED이고 바뀌지 않는다")
    void update_notStopped_conflict() throws Exception {
        for (String status : new String[] {"PREPARING", "RUNNING", "ERROR"}) {
            jdbcTemplate.update("UPDATE camera SET status = ? WHERE id = ?", status, cameraId);
            update(cameraId, "{\"name\":\"고치기\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CAMERA_NOT_STOPPED"));
        }
        jdbcTemplate.update("UPDATE camera SET status = 'STOPPED', run_requested = 1 WHERE id = ?", cameraId);
        update(cameraId, "{\"name\":\"고치기\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMERA_NOT_STOPPED"));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM camera WHERE id = ?", String.class, cameraId)).isEqualTo("캠");
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-019 없는 캠·삭제된 캠은 404")
    void update_notFound() throws Exception {
        update(999_999L, "{\"name\":\"없는 캠\"}").andExpect(status().isNotFound());

        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", cameraId);
        update(cameraId, "{\"name\":\"지워진 캠\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-019 보낸 것이 없거나, 이름이 비었거나 50자를 넘거나, 영상 주소가 255자를 넘으면 400 INVALID_INPUT")
    void update_invalidInput() throws Exception {
        update(cameraId, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        update(cameraId, "{\"name\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        update(cameraId, "{\"name\":\"" + "가".repeat(51) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        update(cameraId, "{\"sourceUri\":\"rtsp://" + "a".repeat(250) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-019 고치면 work_history에 CAMERA_CHANGE(op UPDATE)가 이전·이후 값과 함께 남는다")
    void update_recordsWorkHistory() throws Exception {
        update(cameraId, "{\"name\":\"고친 캠\",\"sourceType\":\"DUMMY\",\"sourceUri\":\"" + DUMMY_URI + "\"}")
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTION")).isEqualTo("CAMERA_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("CAMERA");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(cameraId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("고친 캠");
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        // H2는 JSON 칼럼을 바이트로 돌려준다
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("op").asString()).isEqualTo("UPDATE");
        assertThat(detail.get("zoneId").asLong()).isEqualTo(zoneId);
        assertThat(detail.get("before").get("name").asString()).isEqualTo("캠");
        assertThat(detail.get("before").get("sourceType").asString()).isEqualTo("LIVE");
        assertThat(detail.get("before").get("sourceUri").asString()).isEqualTo(LIVE_URI);
        assertThat(detail.get("after").get("name").asString()).isEqualTo("고친 캠");
        assertThat(detail.get("after").get("sourceType").asString()).isEqualTo("DUMMY");
        assertThat(detail.get("after").get("sourceUri").asString()).isEqualTo(DUMMY_URI);
    }

    @Test
    @DisplayName("FNC-A-019 로그인하지 않으면 401이고 캠은 바뀌지 않는다")
    void update_notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(patch(CAMERAS_URL + cameraId)
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"몰래 수정\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM camera WHERE id = ?", String.class, cameraId)).isEqualTo("캠");
    }

    private ResultActions update(long id, String body) throws Exception {
        return mockMvc.perform(patch(CAMERAS_URL + id)
                .with(AdminTestLogin.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long insertCamera(String status, boolean runRequested, String deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status, run_requested)"
                        + " VALUES (?, '캠', 'LIVE', ?, ?, ?)",
                zoneId,
                LIVE_URI,
                status,
                runRequested);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
