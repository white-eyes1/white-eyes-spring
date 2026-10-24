package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.HeatmapRequest;
import com.whiteeyes.common.stat.dto.HeatmapRequest.DayType;
import com.whiteeyes.common.stat.dto.HeatmapRequest.Item;
import com.whiteeyes.common.stat.dto.HeatmapResponse;
import com.whiteeyes.common.stat.service.HeatmapService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 기간 2099-01-05(월) ~ 01-19 0시(2주). 기대값은 아래 시간별 통계로 손으로 계산했다
/** 시간대별 히트맵(FNC-C-011) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class HeatmapIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String URL = "/api/v1/map/heatmap";
    private static final LocalDate MONDAY = LocalDate.of(2099, 1, 5);
    private static final LocalDateTime FROM = MONDAY.atStartOfDay();
    private static final LocalDateTime TO = MONDAY.plusDays(14).atStartOfDay();

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private HeatmapService heatmapService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private long sajik1;
    private long sajik2;
    private long samcheong;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        sajik1 = insertZone("11110530", "사직동 1구역");
        sajik2 = insertZone("11110530", "사직동 2구역");
        samcheong = insertZone("11110540", "삼청동 1구역");

        // 사직 1구역 9시: 첫째 월 40명(3600초, 유입 6), 둘째 월 10명(1800초, 유입 2), 토 100명(3600초, 유입 10)
        insertHourly(sajik1, MONDAY.atTime(9, 0), "40", "0.4000", 6, 3600);
        insertHourly(sajik1, MONDAY.plusDays(7).atTime(9, 0), "10", "0.1000", 2, 1800);
        insertHourly(sajik1, MONDAY.plusDays(5).atTime(9, 0), "100", "1.0000", 10, 3600);
        // 받은 초 0은 넣지 않는다, 기간 밖도 넣지 않는다
        insertHourly(sajik1, MONDAY.plusDays(1).atTime(9, 0), "999", "9.9900", 999, 0);
        insertHourly(sajik1, TO.withHour(9), "999", "9.9900", 999, 3600);
        // 삼청 9시: 첫째 월 60명
        insertHourly(samcheong, MONDAY.atTime(9, 0), "60", "0.6000", 0, 3600);
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-011 검수 기준: 평일/주말 필터 결과가 다르게 나온다(인원은 받은 초 가중 평균)")
    void weekdayAndWeekendDiffer() {
        HeatmapResponse all = heatmap(regionId("11110530"), Item.COUNT, DayType.ALL);
        HeatmapResponse weekday = heatmap(regionId("11110530"), Item.COUNT, DayType.WEEKDAY);
        HeatmapResponse weekend = heatmap(regionId("11110530"), Item.COUNT, DayType.WEEKEND);

        String key = String.valueOf(sajik1);
        // 평일 9시 (40×3600 + 10×1800) ÷ 5400 = 30, 주말 9시 100, 전체 (40×3600 + 10×1800 + 100×3600) ÷ 9000 = 58
        assertThat(weekday.values().get(key).get(9)).isEqualByComparingTo("30.00");
        assertThat(weekend.values().get(key).get(9)).isEqualByComparingTo("100.00");
        assertThat(all.values().get(key).get(9)).isEqualByComparingTo("58.00");
        assertThat(weekday.values().get(key)).isNotEqualTo(weekend.values().get(key));
        // 데이터 없는 칸은 null, 통계가 없는 구역은 24칸 모두 null
        assertThat(all.values().get(key).get(10)).isNull();
        assertThat(all.values().get(String.valueOf(sajik2))).hasSize(24).containsOnlyNulls();
        assertThat(all.units()).containsExactly(sajik1, sajik2);
        assertThat(all.min()).isEqualByComparingTo("58.00");
        assertThat(all.max()).isEqualByComparingTo("58.00");
    }

    @Test
    @DisplayName("FNC-C-011 밀집도는 받은 초 가중 평균, 유입·유출은 시간당 평균 합계(시간마다 같은 무게)")
    void densityWeightedAndFlowPerHour() {
        HeatmapResponse density = heatmap(regionId("11110530"), Item.DENSITY, DayType.WEEKDAY);
        HeatmapResponse inflow = heatmap(regionId("11110530"), Item.INFLOW, DayType.WEEKDAY);

        String key = String.valueOf(sajik1);
        // (0.4×3600 + 0.1×1800) ÷ 5400 = 0.3
        assertThat(density.values().get(key).get(9)).isEqualByComparingTo("0.3000");
        // (6 + 2) ÷ 2
        assertThat(inflow.values().get(key).get(9)).isEqualByComparingTo("4.0");
    }

    @Test
    @DisplayName("FNC-C-011 시·군·구의 하위 단위는 읍·면·동이고 소속 구역을 합산한다, min·max는 모든 칸에서")
    void sigunguUnitsAreDongs() {
        HeatmapResponse response = heatmap(regionId("11110"), Item.COUNT, DayType.WEEKDAY);

        assertThat(response.units()).containsExactly(regionId("11110530"), regionId("11110540"));
        assertThat(response.values().get(String.valueOf(regionId("11110530"))).get(9)).isEqualByComparingTo("30.00");
        assertThat(response.values().get(String.valueOf(regionId("11110540"))).get(9)).isEqualByComparingTo("60.00");
        assertThat(response.min()).isEqualByComparingTo("30.00");
        assertThat(response.max()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("FNC-C-011 API: units·values·min·max, dayType 기본 ALL, 1년 초과는 400 RANGE_TOO_LONG, 형식 오류는 INVALID_INPUT")
    void api() throws Exception {
        String dong = String.valueOf(regionId("11110530"));
        mockMvc.perform(request(dong, "2099-01-05", "2099-01-19", "COUNT", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units.length()").value(2))
                .andExpect(jsonPath("$.values['" + sajik1 + "'].length()").value(24))
                .andExpect(jsonPath("$.values['" + sajik1 + "'][9]").value(58.0))
                .andExpect(jsonPath("$.values['" + sajik1 + "'][0]").isEmpty())
                .andExpect(jsonPath("$.min").value(58.0))
                .andExpect(jsonPath("$.max").value(58.0));
        // 1년(2099-01-01 ~ 2100-01-01)은 허용, 넘으면 초과
        mockMvc.perform(request(dong, "2099-01-01", "2100-01-01", "COUNT", "WEEKEND")).andExpect(status().isOk());
        mockMvc.perform(request(dong, "2099-01-01", "2100-01-02", "COUNT", "ALL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        // 평균 체류는 히트맵 항목이 아니다
        mockMvc.perform(request(dong, "2099-01-05", "2099-01-19", "DWELL", "ALL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("item"));
        mockMvc.perform(request(dong, "2099-01-05", "2099-01-19", "COUNT", "HOLIDAY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dayType"));
        mockMvc.perform(request("999999", "2099-01-05", "2099-01-19", "COUNT", "ALL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    private HeatmapResponse heatmap(long regionId, Item item, DayType dayType) {
        return heatmapService.findHeatmap(new HeatmapRequest(regionId, FROM, TO, item, dayType));
    }

    private static MockHttpServletRequestBuilder request(
            String regionId, String from, String to, String item, String dayType) {
        MockHttpServletRequestBuilder builder = get(URL).with(AdminTestLogin.admin())
                .param("regionId", regionId)
                .param("from", from)
                .param("to", to)
                .param("item", item);
        return dayType == null ? builder : builder.param("dayType", dayType);
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
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

    // 유입은 북쪽 한 칸에 넣는다(네 방향 합만 본다)
    private void insertHourly(
            long zoneId, LocalDateTime statAt, String countAvg, String density, int inflow, int sampleSec) {
        BigDecimal count = new BigDecimal(countAvg);
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density, in_n,"
                        + " sample_sec) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                zoneId,
                Timestamp.valueOf(statAt),
                count,
                count.intValue(),
                count.intValue(),
                new BigDecimal(density),
                inflow,
                sampleSec);
    }
}
