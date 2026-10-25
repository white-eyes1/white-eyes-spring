package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.ZoneFlowItem;
import com.whiteeyes.common.stat.dto.ZoneFlowRequest;
import com.whiteeyes.common.stat.service.ZoneFlowService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 사직 1구역의 동쪽 이웃 = 삼청 1구역(삼청의 서쪽 = 사직). 그 밖의 방향은 이웃 없음(관측 범위 밖)
/** 구역 간 이동 흐름(FNC-C-010) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ZoneFlowIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String URL = "/api/v1/map/flows";
    private static final LocalDate DAY1 = LocalDate.of(2099, 1, 1);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneFlowService zoneFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private long sajik;
    private long samcheong;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        sajik = insertZone("11110530", "사직동 1구역");
        samcheong = insertZone("11110540", "삼청동 1구역");
        insertNeighbor(sajik, "E", samcheong);
        insertNeighbor(samcheong, "W", sajik);
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-010 검수 기준: 이웃이 있는 방향은 이웃 구역으로, 없는 방향은 관측 범위 밖(null)으로, 반대 방향은 따로 준다")
    void neighborAndOutsideAndOpposite() {
        // 10시: 사직 → 동(삼청) 7명, 북(이웃 없음) 3명 / 삼청 → 서(사직) 4명, 남(이웃 없음) 0명
        insertHourly(DAY1.atTime(10, 0), sajik, 3, 7, 0, 0);
        insertHourly(DAY1.atTime(10, 0), samcheong, 0, 0, 0, 4);

        List<ZoneFlowItem> flows = flows(regionId("11110"), DAY1.atTime(10, 0), DAY1.atTime(11, 0));

        assertThat(flows).containsExactly(
                new ZoneFlowItem(sajik, null, "N", 3),
                new ZoneFlowItem(sajik, samcheong, "E", 7),
                new ZoneFlowItem(samcheong, sajik, "W", 4));
    }

    @Test
    @DisplayName("FNC-C-010 기간을 나눠 1분·시간별·일별 통계에서 유출을 더하고, 기간 밖은 넣지 않는다")
    void sumsAcrossSources() {
        // 기간 1일 23:30 ~ 3일 01:10 → 1분(23:30~24:00) + 일별(2일) + 시간별(3일 0시) + 1분(3일 01:00~01:10)
        insertMinute(DAY1.atTime(23, 40), sajik, 0, 1, 0, 0);
        insertMinute(DAY1.atTime(23, 10), sajik, 0, 100, 0, 0); // 기간 앞
        insertHourly(DAY1.atTime(23, 0), sajik, 0, 100, 0, 0); // 23시는 1분 통계로 읽는다
        insertDaily(DAY1.plusDays(1), sajik, 0, 20, 0, 0);
        insertHourly(DAY1.plusDays(1).atTime(5, 0), sajik, 0, 100, 0, 0); // 2일은 일별로 읽는다
        insertHourly(DAY1.plusDays(2).atTime(0, 0), sajik, 0, 300, 0, 0);
        insertMinute(DAY1.plusDays(2).atTime(1, 5), sajik, 0, 4000, 0, 0);
        insertMinute(DAY1.plusDays(2).atTime(1, 10), sajik, 0, 99999, 0, 0); // to와 같은 분은 기간 밖

        List<ZoneFlowItem> flows =
                flows(regionId("11110"), DAY1.atTime(23, 30), DAY1.plusDays(2).atTime(1, 10));

        assertThat(flows).containsExactly(new ZoneFlowItem(sajik, samcheong, "E", 1 + 20 + 300 + 4000));
    }

    @Test
    @DisplayName("FNC-C-010 지역 밖 이웃도 toZoneId로 주고, 삭제된 구역과 다른 지역의 구역은 출발점이 아니다")
    void onlyActiveZonesInRegionAreSources() {
        long deleted = insertZone("11110530", "사직동 삭제된 구역");
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-01 00:00:00' WHERE id = ?", deleted);
        insertHourly(DAY1.atTime(10, 0), sajik, 0, 7, 0, 0);
        insertHourly(DAY1.atTime(10, 0), samcheong, 0, 0, 0, 4);
        insertHourly(DAY1.atTime(10, 0), deleted, 5, 0, 0, 0);

        List<ZoneFlowItem> sajikDong = flows(regionId("11110530"), DAY1.atTime(10, 0), DAY1.atTime(11, 0));

        // 사직동만: 사직 → 삼청(동 밖이지만 이웃) 7명. 삼청·삭제된 구역의 유출은 없다
        assertThat(sajikDong).containsExactly(new ZoneFlowItem(sajik, samcheong, "E", 7));
    }

    @Test
    @DisplayName("FNC-C-010 API: 이동 목록(관측 범위 밖은 toZoneId null), 기간 오류는 400 INVALID_RANGE, 형식 오류는 INVALID_INPUT")
    void api() throws Exception {
        insertHourly(DAY1.atTime(10, 0), sajik, 3, 7, 0, 0);
        String region = String.valueOf(regionId("11110"));

        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("regionId", region)
                        .param("from", "2099-01-01T10:00:00+09:00")
                        .param("to", "2099-01-01T11:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromZoneId").value(sajik))
                .andExpect(jsonPath("$[0].toZoneId").isEmpty())
                .andExpect(jsonPath("$[0].direction").value("N"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[1].toZoneId").value(samcheong))
                .andExpect(jsonPath("$[1].count").value(7));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("regionId", region)
                        .param("from", "2099-01-01T11:00")
                        .param("to", "2099-01-01T11:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("regionId", region)
                        .param("from", "abc")
                        .param("to", "2099-01-01T11:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("regionId", "999999")
                        .param("from", "2099-01-01T10:00")
                        .param("to", "2099-01-01T11:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    private List<ZoneFlowItem> flows(long regionId, LocalDateTime from, LocalDateTime to) {
        return zoneFlowService.findFlows(new ZoneFlowRequest(regionId, from, to));
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM zone_stat_daily");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private long regionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
    }

    private long insertZone(String dongCode, String name) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000)",
                regionId(dongCode),
                name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private void insertNeighbor(long zoneId, String direction, long neighborId) {
        jdbcTemplate.update(
                "INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, ?, ?)",
                zoneId,
                direction,
                neighborId);
    }

    // 유출은 N·E·S·W 순서. 나머지 칸은 이 시험에서 보지 않는다
    private void insertMinute(LocalDateTime statAt, long zoneId, int outN, int outE, int outS, int outW) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " out_n, out_e, out_s, out_w, sample_sec) VALUES (?, ?, 10, 10, 10, 0.1, ?, ?, ?, ?, 60)",
                zoneId, Timestamp.valueOf(statAt), outN, outE, outS, outW);
    }

    private void insertHourly(LocalDateTime statAt, long zoneId, int outN, int outE, int outS, int outW) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " out_n, out_e, out_s, out_w, sample_sec) VALUES (?, ?, 10, 10, 10, 0.1, ?, ?, ?, ?, 3600)",
                zoneId, Timestamp.valueOf(statAt), outN, outE, outS, outW);
    }

    private void insertDaily(LocalDate statDate, long zoneId, int outN, int outE, int outS, int outW) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_daily (zone_id, stat_date, count_avg, count_max, count_min, density,"
                        + " out_n, out_e, out_s, out_w, sample_sec) VALUES (?, ?, 10, 10, 10, 0.1, ?, ?, ?, ?, 86400)",
                zoneId, Date.valueOf(statDate), outN, outE, outS, outW);
    }
}
