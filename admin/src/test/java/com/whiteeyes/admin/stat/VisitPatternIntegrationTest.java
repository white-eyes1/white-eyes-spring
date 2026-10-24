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
import com.whiteeyes.common.stat.dto.ComparePeriod;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.VisitPatternRequest;
import com.whiteeyes.common.stat.dto.VisitPatternResponse;
import com.whiteeyes.common.stat.service.VisitPatternService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 기간 2099-01-05(월) ~ 01-18(일) 2주. 기대값은 아래 시간별 통계로 손으로 계산했다
/** 방문 패턴(FNC-C-028) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class VisitPatternIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String URL = "/api/v1/dashboard/visit-pattern";
    private static final LocalDate MONDAY = LocalDate.of(2099, 1, 5);
    private static final ComparePeriod TWO_WEEKS = new ComparePeriod(MONDAY, MONDAY.plusDays(13));

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private VisitPatternService visitPatternService;

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

        // 사직 9시: 월 10·30명(두 주), 화 40명, 토 50명, 일 70명
        insertHourly(sajik, MONDAY.atTime(9, 0), "10", 3600);
        insertHourly(sajik, MONDAY.plusDays(7).atTime(9, 0), "30", 3600);
        insertHourly(sajik, MONDAY.plusDays(1).atTime(9, 0), "40", 3600);
        insertHourly(sajik, MONDAY.plusDays(5).atTime(9, 0), "50", 3600);
        insertHourly(sajik, MONDAY.plusDays(6).atTime(9, 0), "70", 3600);
        // 수 9시는 받은 초 0(데이터 없는 시간), 기간 다음 날(월)은 기간 밖
        insertHourly(sajik, MONDAY.plusDays(2).atTime(9, 0), "999", 0);
        insertHourly(sajik, MONDAY.plusDays(14).atTime(9, 0), "999", 3600);
        // 삼청: 첫째 월 9시 20명
        insertHourly(samcheong, MONDAY.atTime(9, 0), "20", 3600);
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-028 검수 기준: 요일 × 시간 칸 값과 평일·주말 행이 손으로 계산한 값과 일치하고, 데이터 없는 칸은 null")
    void zone_matchesHandCalculation() {
        VisitPatternResponse pattern = pattern(DashboardTargetType.ZONE, sajik);

        assertThat(pattern.cells()).hasSize(168);
        assertThat(pattern.cells().get(0)).isEqualTo(new VisitPatternResponse.Cell(1, 0, null));
        assertThat(pattern.cells().get(167)).isEqualTo(new VisitPatternResponse.Cell(7, 23, null));
        // 월 9시 (10 + 30) ÷ 2, 화 9시 40, 수 9시 받은 초 0 → null, 토 50, 일 70
        assertThat(cell(pattern, 1, 9)).isEqualByComparingTo("20.0");
        assertThat(cell(pattern, 2, 9)).isEqualByComparingTo("40.0");
        assertThat(cell(pattern, 3, 9)).isNull();
        assertThat(cell(pattern, 6, 9)).isEqualByComparingTo("50.0");
        assertThat(cell(pattern, 7, 9)).isEqualByComparingTo("70.0");
        // 평일 9시 = 평일의 모든 9시 값 평균 (10 + 30 + 40) ÷ 3 = 26.666… → 26.7, 주말 9시 (50 + 70) ÷ 2
        assertThat(pattern.weekdayAvg()).hasSize(24);
        assertThat(pattern.weekdayAvg().get(9)).isEqualByComparingTo("26.7");
        assertThat(pattern.weekendAvg()).hasSize(24);
        assertThat(pattern.weekendAvg().get(9)).isEqualByComparingTo("60.0");
        assertThat(pattern.weekdayAvg().get(10)).isNull();
    }

    @Test
    @DisplayName("FNC-C-028 지역은 같은 시각 소속 구역의 인원을 더한 뒤 평균한다")
    void region_sumsZonesBeforeAveraging() {
        VisitPatternResponse pattern = pattern(DashboardTargetType.REGION, regionId("11110"));

        // 월 9시: 첫째 주 10 + 20 = 30, 둘째 주 30 → (30 + 30) ÷ 2
        assertThat(cell(pattern, 1, 9)).isEqualByComparingTo("30.0");
        assertThat(cell(pattern, 2, 9)).isEqualByComparingTo("40.0");
    }

    @Test
    @DisplayName("FNC-C-028 API: 168칸·평일·주말 24개, 기간을 비우면 어제까지 28일")
    void api_shapeAndDefaultPeriod() throws Exception {
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("from", "2099-01-05")
                        .param("to", "2099-01-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(168))
                .andExpect(jsonPath("$.cells[9].weekday").value(1))
                .andExpect(jsonPath("$.cells[9].hour").value(9))
                .andExpect(jsonPath("$.cells[9].avgCount").value(20.0))
                .andExpect(jsonPath("$.cells[0].avgCount").isEmpty())
                .andExpect(jsonPath("$.weekdayAvg.length()").value(24))
                .andExpect(jsonPath("$.weekendAvg[9]").value(60.0));
        // 기간을 비우면 어제까지 28일(2099년 통계는 들어가지 않는다)
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells[9].avgCount").isEmpty());

        VisitPatternRequest defaults =
                VisitPatternRequest.parse(DashboardTargetType.ZONE, sajik, null, null, LocalDate.of(2099, 2, 1));
        assertThat(defaults.period()).isEqualTo(new ComparePeriod(LocalDate.of(2099, 1, 4), LocalDate.of(2099, 1, 31)));
    }

    @Test
    @DisplayName("FNC-C-028 기간 1년 초과·뒤집힌 기간은 400 INVALID_RANGE, 한쪽만 주거나 형식 오류는 400 INVALID_INPUT, 없는 대상은 404")
    void api_errors() throws Exception {
        String zone = String.valueOf(sajik);
        // 2099-01-01 ~ 12-31은 1년(허용), 2100-01-01까지는 초과
        mockMvc.perform(request(zone, "2099-01-01", "2099-12-31")).andExpect(status().isOk());
        mockMvc.perform(request(zone, "2099-01-01", "2100-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(request(zone, "2099-01-10", "2099-01-09"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", zone)
                        .param("from", "2099-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        mockMvc.perform(request(zone, "2099/01/01", "2099-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "REGION").param("targetId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    private VisitPatternResponse pattern(DashboardTargetType targetType, long targetId) {
        return visitPatternService.findPattern(new VisitPatternRequest(targetType, targetId, TWO_WEEKS));
    }

    private static BigDecimal cell(VisitPatternResponse pattern, int weekday, int hour) {
        return pattern.cells().get((weekday - 1) * 24 + hour).avgCount();
    }

    private MockHttpServletRequestBuilder request(
            String zoneId, String from, String to) {
        return get(URL).with(AdminTestLogin.admin())
                .param("targetType", "ZONE")
                .param("targetId", zoneId)
                .param("from", from)
                .param("to", to);
    }

    private void deleteRows() {
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

    private void insertHourly(long zoneId, LocalDateTime statAt, String countAvg, int sampleSec) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec)"
                        + " VALUES (?, ?, ?, 0, 0, 0, ?)",
                zoneId,
                Timestamp.valueOf(statAt),
                new BigDecimal(countAvg),
                sampleSec);
    }
}
