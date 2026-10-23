package com.whiteeyes.admin.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 구역 삭제(FNC-A-017). 검수 기준: 이력 있음/없음 두 경우 확인. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ZoneDeleteTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String ZONES_URL = "/api/v1/manage/zones/";
    private static final String SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private long dongId;
    private long zoneId;
    private long otherZoneId;

    @BeforeEach
    void prepare() {
        clearAll();
        regionLoadService.load(REGION_FILE);
        dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = '삼청동'", Long.class);
        zoneId = insertZone("삼청동 1구역");
        otherZoneId = insertZone("삼청동 2구역");
    }

    // camera·zone_neighbor·zone·work_history를 남기면 뒤에 도는 다른 테스트가 region을 지우다 FK로 실패한다
    @AfterEach
    void cleanUp() {
        clearAll();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-017 가동 이력이 없으면 이웃 행·캠·구역을 실제로 지우고 HARD를 돌려준다")
    void noHistory_hardDelete() throws Exception {
        insertCamera(zoneId, "STOPPED", null, null);
        insertCamera(zoneId, "ERROR", null, null);
        insertNeighbor(zoneId, "E", otherZoneId);
        insertNeighbor(otherZoneId, "W", zoneId);

        removeZone(zoneId).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("HARD"));

        assertThat(count("zone WHERE id = " + zoneId)).isZero();
        assertThat(count("camera WHERE zone_id = " + zoneId)).isZero();
        assertThat(count("zone_neighbor")).isZero();
        assertThat(count("zone WHERE id = " + otherZoneId)).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-017 캠이 없는 구역도 실제로 지운다(이력 없음)")
    void noCamera_hardDelete() throws Exception {
        removeZone(zoneId).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("HARD"));

        assertThat(count("zone WHERE id = " + zoneId)).isZero();
    }

    @Test
    @DisplayName("FNC-A-017 가동 이력이 있으면 구역·소속 캠에 삭제 표시만 하고 이 구역의 이웃 행은 모두 지운다")
    void withHistory_softDelete() throws Exception {
        long camera = insertCamera(zoneId, "STOPPED", LocalDateTime.now().minusDays(2), null);
        long unstarted = insertCamera(zoneId, "STOPPED", null, null);
        long third = insertZone("삼청동 3구역");
        insertNeighbor(zoneId, "E", otherZoneId);
        insertNeighbor(otherZoneId, "W", zoneId);
        insertNeighbor(third, "N", zoneId);
        insertNeighbor(third, "S", otherZoneId);

        removeZone(zoneId).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SOFT"));

        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM zone WHERE id = ?", Timestamp.class, zoneId))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM camera WHERE id = ?", Timestamp.class, camera))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM camera WHERE id = ?", Timestamp.class, unstarted))
                .isNotNull();
        // 이 구역이 들어간 행(기준이거나 이웃으로 가리키는 것)만 지우고 다른 구역끼리의 행은 남긴다
        assertThat(count("zone_neighbor")).isEqualTo(1);
        assertThat(count("zone_neighbor WHERE zone_id = " + third + " AND direction = 'S'")).isEqualTo(1);
        assertThat(count("zone WHERE id = " + otherZoneId + " AND deleted_at IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-017 삭제 표시된 구역은 구역 목록에서 빠지고, 같은 이름으로 새 구역을 만들 수 있다")
    void softDeleted_zoneDisappearsFromList() throws Exception {
        insertCamera(zoneId, "STOPPED", LocalDateTime.now().minusDays(1), null);

        removeZone(zoneId).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/zones").param("dongId", String.valueOf(dongId)).with(AdminTestLogin.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("삼청동 2구역"));
        assertThat(count("zone")).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-017 이미 삭제된 캠에 가동 이력이 있어도 이력 있음이고, 그 캠의 삭제 시각은 바뀌지 않는다")
    void historyOnDeletedCamera_softAndKeepsDeletedAt() throws Exception {
        LocalDateTime earlier = LocalDateTime.now().minusDays(5).withNano(0);
        long deletedCamera = insertCamera(zoneId, "STOPPED", LocalDateTime.now().minusDays(9), earlier);

        removeZone(zoneId).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SOFT"));

        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM camera WHERE id = ?", Timestamp.class, deletedCamera)
                        .toLocalDateTime())
                .isEqualTo(earlier);
    }

    @Test
    @DisplayName("FNC-A-017 준비 중(PREPARING)이거나 가동 중(RUNNING)인 캠이 있으면 409 CAMERA_RUNNING이고 아무것도 바뀌지 않는다")
    void startingOrRunningCamera_conflict() throws Exception {
        insertCamera(zoneId, "STOPPED", null, null);
        long preparing = insertCamera(zoneId, "PREPARING", LocalDateTime.now().minusMinutes(1), null);
        insertNeighbor(zoneId, "E", otherZoneId);

        removeZone(zoneId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMERA_RUNNING"));

        jdbcTemplate.update("UPDATE camera SET status = 'RUNNING' WHERE id = ?", preparing);
        removeZone(zoneId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMERA_RUNNING"));

        assertThat(count("zone WHERE id = " + zoneId + " AND deleted_at IS NULL")).isEqualTo(1);
        assertThat(count("camera WHERE zone_id = " + zoneId + " AND deleted_at IS NULL")).isEqualTo(2);
        assertThat(count("zone_neighbor")).isEqualTo(1);
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-017 정지(STOPPED)·오류(ERROR) 캠이거나, 이미 삭제된 캠이 가동 중 상태로 남아 있어도 삭제할 수 있다")
    void stoppedErrorOrDeletedRunning_allowed() throws Exception {
        insertCamera(zoneId, "ERROR", LocalDateTime.now().minusDays(1), null);
        insertCamera(zoneId, "RUNNING", LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(2));

        removeZone(zoneId).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SOFT"));
    }

    @Test
    @DisplayName("FNC-A-017 없는 구역·이미 삭제된 구역은 404 ZONE_NOT_FOUND")
    void notFound() throws Exception {
        removeZone(999_999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        insertCamera(zoneId, "STOPPED", LocalDateTime.now().minusDays(1), null);
        removeZone(zoneId).andExpect(status().isOk());
        removeZone(zoneId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-017 작업 기록(ZONE_CHANGE, op DELETE)에 HARD/SOFT와 구역 이름·캠 수가 남는다")
    void recordsWorkHistory() throws Exception {
        insertCamera(zoneId, "STOPPED", null, null);
        removeZone(zoneId).andExpect(status().isOk());
        insertCamera(otherZoneId, "STOPPED", LocalDateTime.now().minusDays(1), null);
        removeZone(otherZoneId).andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("SELECT * FROM work_history ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("ACTION")).isEqualTo("ZONE_CHANGE");
        assertThat(rows.get(0).get("TARGET_NAME")).isEqualTo("삼청동 1구역");
        JsonNode hard = detail(rows.get(0));
        assertThat(hard.get("op").asString()).isEqualTo("DELETE");
        assertThat(hard.get("mode").asString()).isEqualTo("HARD");
        assertThat(hard.get("cameraCount").asInt()).isEqualTo(1);
        JsonNode soft = detail(rows.get(1));
        assertThat(soft.get("mode").asString()).isEqualTo("SOFT");
        assertThat(soft.get("name").asString()).isEqualTo("삼청동 2구역");
    }

    @Test
    @DisplayName("FNC-A-017 로그인하지 않으면 401이고 구역은 그대로다")
    void notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(delete(ZONES_URL + zoneId).with(anonymous())).andExpect(status().isUnauthorized());

        assertThat(count("zone WHERE id = " + zoneId)).isEqualTo(1);
    }

    private ResultActions removeZone(long id) throws Exception {
        return mockMvc.perform(delete(ZONES_URL + id).with(AdminTestLogin.admin()));
    }

    // H2는 JSON 칼럼을 바이트로 돌려준다
    private JsonNode detail(Map<String, Object> row) throws Exception {
        return jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
    }

    private long insertZone(String name) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, 39190.00, ? FORMAT JSON, 37.602000, 127.002000)",
                dongId,
                name,
                SQUARE);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private long insertCamera(long zone, String status, LocalDateTime firstStartedAt, LocalDateTime deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status, first_started_at, deleted_at)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', ?, ?, ?)",
                zone,
                status,
                firstStartedAt == null ? null : Timestamp.valueOf(firstStartedAt),
                deletedAt == null ? null : Timestamp.valueOf(deletedAt));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private void insertNeighbor(long zone, String direction, long neighbor) {
        jdbcTemplate.update(
                "INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, ?, ?)", zone, direction, neighbor);
    }

    private long count(String fromWhere) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Long.class);
    }
}
