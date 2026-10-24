package com.whiteeyes.admin.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 구역 수정(FNC-A-015). 검수 기준: 동 경계를 넘는 수정 거부. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ZoneUpdateTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String ZONES_URL = "/api/v1/manage/zones/";

    // 삼청동(경도 127.00~127.05, 위도 37.55~37.65) 안의 0.002° × 0.002° 사각형, 약 39,190㎡
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    // 같은 삼청동 안의 더 작은 사각형(0.001° × 0.001°, 약 9,800㎡)
    private static final String SAMCHEONG_SMALL =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.010,37.610],[127.011,37.610],[127.011,37.611],[127.010,37.611],[127.010,37.610]]]}";
    // 소공동(경도 127.05~127.10, 위도 37.55~37.60) 안의 사각형
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final String OUTSIDE_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[128.0,35.0],[128.002,35.0],[128.002,35.002],[128.0,35.002],[128.0,35.0]]]}";
    private static final String BOW_TIE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.603],[127.003,37.601],[127.001,37.603],[127.001,37.601]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private long zoneId;

    @BeforeEach
    void prepare() {
        clearAll();
        regionLoadService.load(REGION_FILE);
        zoneId = insertZone("삼청동", "삼청동 1구역", SAMCHEONG_SQUARE, 39_190.00);
    }

    // zone·work_history를 남기면 뒤에 도는 다른 테스트가 region을 지우다 실패하거나 기록 수가 어긋난다
    @AfterEach
    void cleanUp() {
        clearAll();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-015 이름만 보내면 이름만 바뀌고 다각형·면적은 그대로다")
    void rename_only() throws Exception {
        update(zoneId, "{\"name\":\"삼청동 새 구역\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(zoneId))
                .andExpect(jsonPath("$.name").value("삼청동 새 구역"))
                .andExpect(jsonPath("$.areaM2").value(39190.0));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, area_m2, center_lat FROM zone WHERE id = ?", zoneId);
        assertThat(row.get("NAME")).isEqualTo("삼청동 새 구역");
        assertThat(((Number) row.get("AREA_M2")).doubleValue()).isEqualTo(39_190.0);
    }

    @Test
    @DisplayName("FNC-A-015 다각형만 보내면 서버가 면적·중심점을 다시 계산하고 이름은 그대로다")
    void reshape_only_recalculates() throws Exception {
        update(zoneId, "{\"boundary\":" + SAMCHEONG_SMALL + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("삼청동 1구역"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, area_m2, center_lat, center_lng, boundary FROM zone WHERE id = ?", zoneId);
        assertThat(row.get("NAME")).isEqualTo("삼청동 1구역");
        assertThat(((Number) row.get("AREA_M2")).doubleValue()).isCloseTo(9_800.0, within(9_800.0 * 0.02));
        assertThat(((Number) row.get("CENTER_LNG")).doubleValue()).isBetween(127.010, 127.011);
        assertThat(((Number) row.get("CENTER_LAT")).doubleValue()).isBetween(37.610, 37.611);
        assertThat(boundaryText(row.get("BOUNDARY"))).contains("127.011");
    }

    @Test
    @DisplayName("FNC-A-015 이름과 다각형을 함께 보낼 수 있고 이름 앞뒤 공백은 지운다")
    void rename_and_reshape_trimmed() throws Exception {
        update(zoneId, "{\"name\":\"  작은 구역  \",\"boundary\":" + SAMCHEONG_SMALL + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("작은 구역"));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM zone WHERE id = ?", String.class, zoneId))
                .isEqualTo("작은 구역");
    }

    @Test
    @DisplayName("FNC-A-015 면적은 요청 값이 아니라 서버가 계산한 값을 쓴다")
    void ignores_client_calculated_values() throws Exception {
        update(zoneId, "{\"boundary\":" + SAMCHEONG_SMALL + ",\"areaM2\":1,\"dongId\":999999}")
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT area_m2 FROM zone WHERE id = ?", Number.class, zoneId)
                        .doubleValue())
                .isGreaterThan(5_000);
        assertThat(jdbcTemplate.queryForObject("SELECT dong_id FROM zone WHERE id = ?", Long.class, zoneId))
                .isEqualTo(dongId("삼청동"));
    }

    @Test
    @DisplayName("FNC-A-015 다른 동으로 옮기는 수정은 400 DONG_CHANGE_NOT_ALLOWED이고 아무것도 바뀌지 않는다")
    void moveToOtherDong_rejected() throws Exception {
        update(zoneId, "{\"name\":\"이름도 같이\",\"boundary\":" + SOGONG_SQUARE + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DONG_CHANGE_NOT_ALLOWED"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, area_m2, dong_id FROM zone WHERE id = ?", zoneId);
        assertThat(row.get("NAME")).isEqualTo("삼청동 1구역");
        assertThat(((Number) row.get("AREA_M2")).doubleValue()).isEqualTo(39_190.0);
        assertThat(((Number) row.get("DONG_ID")).longValue()).isEqualTo(dongId("삼청동"));
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-015 서비스 지역 밖이면 400 OUT_OF_SERVICE_AREA, 꼬인 다각형이면 400 INVALID_POLYGON")
    void invalid_polygons_rejected() throws Exception {
        update(zoneId, "{\"boundary\":" + OUTSIDE_SQUARE + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUT_OF_SERVICE_AREA"));
        update(zoneId, "{\"boundary\":" + BOW_TIE + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POLYGON"));

        assertThat(jdbcTemplate.queryForObject("SELECT area_m2 FROM zone WHERE id = ?", Number.class, zoneId)
                        .doubleValue())
                .isEqualTo(39_190.0);
    }

    @Test
    @DisplayName("FNC-A-015 같은 동의 다른 구역과 이름이 같으면 409 DUPLICATE_ZONE_NAME")
    void duplicateName_conflict() throws Exception {
        insertZone("삼청동", "삼청동 2구역", SAMCHEONG_SMALL, 9_800.00);

        update(zoneId, "{\"name\":\"삼청동 2구역\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ZONE_NAME"));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM zone WHERE id = ?", String.class, zoneId))
                .isEqualTo("삼청동 1구역");
    }

    @Test
    @DisplayName("FNC-A-015 자기 이름 그대로, 다른 동의 같은 이름, 삭제된 구역의 이름은 쓸 수 있다")
    void sameNameAllowed_cases() throws Exception {
        insertZone("소공동", "소공 구역", SOGONG_SQUARE, 39_000.00);
        long deleted = insertZone("삼청동", "지워진 구역", SAMCHEONG_SMALL, 9_800.00);
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);

        update(zoneId, "{\"name\":\"삼청동 1구역\"}").andExpect(status().isOk());
        update(zoneId, "{\"name\":\"소공 구역\"}").andExpect(status().isOk());
        update(zoneId, "{\"name\":\"지워진 구역\"}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-015 없는 구역·삭제된 구역은 404 ZONE_NOT_FOUND")
    void notFound() throws Exception {
        update(999_999L, "{\"name\":\"없는 구역\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", zoneId);
        update(zoneId, "{\"name\":\"지워진 뒤 수정\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-015 name·boundary 둘 다 없거나, 이름이 비었거나 50자를 넘으면 400 INVALID_INPUT")
    void invalid_input() throws Exception {
        update(zoneId, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        update(zoneId, "{\"name\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        update(zoneId, "{\"name\":\"" + "가".repeat(51) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-015 수정하면 work_history에 ZONE_CHANGE(op UPDATE)가 이전·이후 이름·면적과 함께 남는다")
    void records_work_history() throws Exception {
        update(zoneId, "{\"name\":\"작은 구역\",\"boundary\":" + SAMCHEONG_SMALL + "}").andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTION")).isEqualTo("ZONE_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ZONE");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(zoneId);
        assertThat(row.get("TARGET_NAME")).isEqualTo("작은 구역");
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        // H2는 JSON 칼럼을 바이트로 돌려준다
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("op").asString()).isEqualTo("UPDATE");
        assertThat(detail.get("before").get("name").asString()).isEqualTo("삼청동 1구역");
        assertThat(detail.get("before").get("areaM2").asDouble()).isEqualTo(39_190.0);
        assertThat(detail.get("after").get("name").asString()).isEqualTo("작은 구역");
        assertThat(detail.get("after").get("areaM2").asDouble()).isLessThan(20_000);
    }

    @Test
    @DisplayName("FNC-A-015 로그인하지 않으면 401이고 구역은 바뀌지 않는다")
    void notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(patch(ZONES_URL + zoneId)
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"몰래 수정\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM zone WHERE id = ?", String.class, zoneId))
                .isEqualTo("삼청동 1구역");
    }

    private ResultActions update(long id, String body) throws Exception {
        return mockMvc.perform(patch(ZONES_URL + id)
                .with(AdminTestLogin.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long dongId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = ?", Long.class, name);
    }

    private long insertZone(String dongName, String name, String boundary, double area) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, ?, ? FORMAT JSON, 37.602000, 127.002000)",
                dongId(dongName),
                name,
                area,
                boundary);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private String boundaryText(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
