package com.whiteeyes.admin.camera;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
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

/**
 * FNC-A-027 가동 대상 캠 목록 제공. 내부 포트(8082)와 X-Internal-Token으로 들어오는 요청을 MockMvc로 흉내 낸다.
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class DesiredCameraTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String DESIRED_URL = CONTEXT_PATH + "/internal/v1/cameras/desired";
    private static final int INTERNAL_PORT = 8082;
    private static final String TOKEN = "test-internal-token";
    private static final String REGION_CODE = "99999971";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private long dongId;

    // camera → zone → region 순으로 외래키가 걸려 있어 이 테스트가 넣은 것만 순서대로 지운다
    @BeforeEach
    void prepare() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', ?, '가동 목록 테스트동', '{}', 37.600000, 127.002000)", REGION_CODE);
        dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, REGION_CODE);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM event WHERE zone_id IN"
                + " (SELECT id FROM zone WHERE dong_id IN (SELECT id FROM region WHERE code = ?))", REGION_CODE);
        jdbcTemplate.update("DELETE FROM camera WHERE zone_id IN"
                + " (SELECT id FROM zone WHERE dong_id IN (SELECT id FROM region WHERE code = ?))", REGION_CODE);
        jdbcTemplate.update(
                "DELETE FROM zone WHERE dong_id IN (SELECT id FROM region WHERE code = ?)", REGION_CODE);
        jdbcTemplate.update("DELETE FROM region WHERE code = ?", REGION_CODE);
    }

    @Test
    @DisplayName("FNC-A-027 run_requested=1인 캠만 번호 순으로 주고, 오류 상태 캠도 포함한다")
    void desired_returnsRunRequestedCamerasIncludingError() throws Exception {
        long running = insertCamera("구역1", "DUMMY", "dummy/videos/a.mp4", true, "RUNNING", false);
        long errored = insertCamera("구역2", "LIVE", "rtsp://cam/2", true, "ERROR", false);
        insertCamera("구역3", "DUMMY", "dummy/videos/c.mp4", false, "STOPPED", false);

        request(TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cameraId").value(running))
                .andExpect(jsonPath("$[0].sourceType").value("DUMMY"))
                .andExpect(jsonPath("$[0].sourceUri").value("dummy/videos/a.mp4"))
                .andExpect(jsonPath("$[1].cameraId").value(errored))
                .andExpect(jsonPath("$[1].sourceType").value("LIVE"))
                .andExpect(jsonPath("$[1].sourceUri").value("rtsp://cam/2"));
    }

    @Test
    @DisplayName("FNC-A-027 삭제된 캠은 run_requested=1이어도 주지 않는다")
    void desired_excludesDeletedCamera() throws Exception {
        insertCamera("구역1", "DUMMY", "dummy/videos/a.mp4", true, "RUNNING", true);

        request(TOKEN).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("FNC-A-027 토큰이 없거나 틀리면 401")
    void desired_withoutValidToken_unauthorized() throws Exception {
        request(null).andExpect(status().isUnauthorized());
        request("wrong-token").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-027 웹 포트(8081)로는 열리지 않는다")
    void desired_onWebPort_notFound() throws Exception {
        mockMvc.perform(get(DESIRED_URL)
                        .contextPath(CONTEXT_PATH)
                        .header("X-Internal-Token", TOKEN)
                        .with(r -> {
                            r.setLocalPort(8081);
                            return r;
                        }))
                .andExpect(status().isNotFound());
    }

    private ResultActions request(String token) throws Exception {
        var builder = get(DESIRED_URL).contextPath(CONTEXT_PATH).with(r -> {
            r.setLocalPort(INTERNAL_PORT);
            return r;
        });
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return mockMvc.perform(builder);
    }

    // 구역당 캠 1대 규칙이 있어 캠마다 구역을 따로 만든다
    private long insertCamera(
            String zoneName, String sourceType, String sourceUri, boolean runRequested, String status,
            boolean deleted) {
        jdbcTemplate.update("INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000)", dongId, zoneName);
        long zoneId = jdbcTemplate.queryForObject(
                "SELECT id FROM zone WHERE dong_id = ? AND name = ?", Long.class, dongId, zoneName);
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, " + (deleted ? "CURRENT_TIMESTAMP" : "NULL") + ")",
                zoneId, zoneName + " 캠", sourceType, sourceUri, runRequested, status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }
}
