package com.whiteeyes.admin.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트에서 다시보기 이동(FNC-A-040)의 보관 기간 판정이 RECORDING_RETENTION_DAYS 설정을 따르는지 확인한다.
 * 설정을 7일로 낮춰 두고 상세 응답의 playbackAvailable(상세 창 영상 영역·버튼 활성 기준)을 본다.
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "retention.recording-days=7")
@AutoConfigureMockMvc
class EventDetailRetentionTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
    void prepare() {
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        zoneId = zoneRegisterService.register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE)).id();
    }

    // event → zone → region 순으로 외래키가 걸려 있어 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        clearAll();
    }

    @Test
    @DisplayName("FNC-A-040 보관 기간을 7일로 설정하면 6일 23시간 전 이벤트는 true, 7일 1분 전 이벤트는 false")
    void playbackAvailable_followsRetentionSetting() throws Exception {
        LocalDateTime now = LocalDateTime.now(KST);
        long inside = insertCongestion(now.minusDays(6).minusHours(23));
        long outside = insertCongestion(now.minusDays(7).minusMinutes(1));

        mockMvc.perform(get("/api/v1/events/{id}", inside))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playbackAvailable").value(true));
        mockMvc.perform(get("/api/v1/events/{id}", outside))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playbackAvailable").value(false));
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history WHERE target_type = 'EVENT' OR action = 'ZONE_CHANGE'");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    private long insertCongestion(LocalDateTime startedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, started_at, peak_level, peak_value, peak_count, threshold_snapshot, status)
                VALUES ('CONGESTION', ?, ?, 2, '0.8000', 10, '{"warnDensity":0.7,"dangerDensity":1.5}' FORMAT JSON, 'UNCHECKED')
                """,
                zoneId,
                startedAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM event", Long.class);
    }
}
