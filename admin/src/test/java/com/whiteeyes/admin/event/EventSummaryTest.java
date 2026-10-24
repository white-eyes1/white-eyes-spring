package com.whiteeyes.admin.event;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
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

/** 이벤트 요약(FNC-A-036 처리 순서 3) GET /api/v1/events/summary. 목록과 같은 조건에서 처리 상태 조건만 뺀다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class EventSummaryTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SUMMARY_URL = "/api/v1/events/summary";
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);

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
    void prepare() {
        AdminTestLogin.loginForDirectCalls();
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        samcheongZoneId = zoneRegisterService.register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE)).id();
        AdminTestLogin.loginForDirectCalls();
        sogongZoneId = zoneRegisterService.register("소공동 1구역", jsonMapper.readTree(SOGONG_SQUARE)).id();
    }

    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-036 이벤트가 없으면 모두 0")
    void summary_noEvents_zeros() throws Exception {
        mockMvc.perform(get(SUMMARY_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.unchecked").value(0))
                .andExpect(jsonPath("$.checked").value(0))
                .andExpect(jsonPath("$.resolved").value(0));
    }

    @Test
    @DisplayName("FNC-A-036 처리 상태별 건수와 전체 건수를 센다")
    void summary_countsByStatus() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, 0, "UNCHECKED");
        insertEvent("SURGE", samcheongZoneId, 1, "UNCHECKED");
        insertEvent("CAM_FAULT", sogongZoneId, 2, "UNCHECKED");
        insertEvent("CONGESTION", sogongZoneId, 3, "CHECKED");
        insertEvent("CONGESTION", sogongZoneId, 4, "RESOLVED");
        insertEvent("CONGESTION", sogongZoneId, 5, "RESOLVED");

        mockMvc.perform(get(SUMMARY_URL))
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.unchecked").value(3))
                .andExpect(jsonPath("$.checked").value(1))
                .andExpect(jsonPath("$.resolved").value(2));
    }

    @Test
    @DisplayName("FNC-A-036 statuses를 줘도 요약은 처리 상태 조건을 무시한다")
    void summary_ignoresStatuses() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, 0, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, 1, "RESOLVED");

        mockMvc.perform(get(SUMMARY_URL).param("statuses", "UNCHECKED"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.unchecked").value(1))
                .andExpect(jsonPath("$.resolved").value(1));
    }

    @Test
    @DisplayName("FNC-A-036 regionId 조건은 그 지역 아래 구역의 이벤트만 센다")
    void summary_appliesRegionId() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, 0, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, 1, "RESOLVED");
        insertEvent("CONGESTION", sogongZoneId, 2, "UNCHECKED");
        long jongno = jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = ?", Long.class, "종로구");

        mockMvc.perform(get(SUMMARY_URL).param("regionId", String.valueOf(jongno)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.unchecked").value(1))
                .andExpect(jsonPath("$.resolved").value(1));
    }

    @Test
    @DisplayName("FNC-A-036 기간·구역·종류·검색어 조건은 목록과 같게 적용한다")
    void summary_appliesSameFiltersAsList() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, 0, "UNCHECKED");
        insertEvent("SURGE", samcheongZoneId, 1, "CHECKED");
        insertEvent("CONGESTION", sogongZoneId, 2, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, 24 * 60, "UNCHECKED"); // 하루 뒤

        mockMvc.perform(get(SUMMARY_URL)
                        .param("from", "2026-10-12T00:00:00+09:00")
                        .param("to", "2026-10-13T00:00:00+09:00")
                        .param("zoneId", String.valueOf(samcheongZoneId)))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get(SUMMARY_URL).param("types", "SURGE")).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get(SUMMARY_URL).param("keyword", "소공동")).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("FNC-A-036 목록의 총 건수는 같은 조건 요약의 total과 같다")
    void summary_totalMatchesListTotal() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, 0, "UNCHECKED");
        insertEvent("SURGE", sogongZoneId, 1, "RESOLVED");

        mockMvc.perform(get(SUMMARY_URL).param("types", "SURGE")).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/v1/events").param("types", "SURGE"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("FNC-A-036 종류를 여러 개 줘도 요약과 목록 건수가 같다")
    void summary_multipleTypes_sameAsList() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, 0, "UNCHECKED");
        insertEvent("SURGE", samcheongZoneId, 1, "CHECKED");
        insertEvent("CAM_FAULT", sogongZoneId, 2, "RESOLVED");

        mockMvc.perform(get(SUMMARY_URL).param("types", "CONGESTION").param("types", "SURGE"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.unchecked").value(1))
                .andExpect(jsonPath("$.checked").value(1))
                .andExpect(jsonPath("$.resolved").value(0));
        mockMvc.perform(get("/api/v1/events").param("types", "CONGESTION").param("types", "SURGE"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("FNC-A-036 없는 지역으로 거르면 404 REGION_NOT_FOUND")
    void summary_unknownRegion_404() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).param("regionId", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-036 regionId와 zoneId를 함께 주면 400")
    void summary_conflictingScope_400() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).param("regionId", "1").param("zoneId", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-036 로그인하지 않으면 401")
    void summary_anonymous_401() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).with(anonymous())).andExpect(status().isUnauthorized());
    }

    private void insertEvent(String type, long zoneId, int minutesAfterT0, String status) {
        jdbcTemplate.update(
                "INSERT INTO event (type, zone_id, started_at, threshold_snapshot, status) VALUES (?, ?, ?, '{}', ?)",
                type,
                zoneId,
                T0.plusMinutes(minutesAfterT0),
                status);
    }
}
