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

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.TrendResponse;
import com.whiteeyes.common.stat.service.TrendService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 오늘 2099-01-15 → 대상 기간 2099-01-08 ~ 01-14(어제까지 7일). 기대값은 아래 시간별 통계로 손으로 계산했다
/** 이용량 증감 요약(FNC-C-019) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrendIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String URL = "/api/v1/dashboard/trend";
    private static final LocalDate TODAY = LocalDate.of(2099, 1, 15);
    // 대상 기간 안의 한 시각(2099-01-12 9시)
    private static final LocalDateTime TARGET_HOUR = LocalDate.of(2099, 1, 12).atTime(9, 0);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private TrendService trendService;

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
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-019 어제까지 7일을 7일·28일·364일 전 같은 7일과 같은 요일·시간대끼리 비교하고, 기준이 없으면 null")
    void zone_ratesAgainstWeekMonthYear() {
        insertHourly(sajik, TARGET_HOUR, "60");
        // 7일 전 같은 요일·시간 50명 → +20.0%, 28일 전 120명 → -50.0%, 364일 전은 없음 → null
        insertHourly(sajik, TARGET_HOUR.minusDays(7), "50");
        insertHourly(sajik, TARGET_HOUR.minusDays(28), "120");
        // 대상 기간 밖(오늘)의 값은 넣지 않는다
        insertHourly(sajik, TODAY.atTime(9, 0), "999");

        TrendResponse trend = trendService.findTrend(DashboardTargetType.ZONE, sajik, TODAY);

        assertThat(trend.weekRate()).isEqualByComparingTo("20.0");
        assertThat(trend.monthRate()).isEqualByComparingTo("-50.0");
        assertThat(trend.yearRate()).isNull();
    }

    @Test
    @DisplayName("FNC-C-019 지역은 소속 구역의 같은 시각 인원을 더해 비교한다")
    void region_sumsZones() {
        insertHourly(sajik, TARGET_HOUR, "60");
        insertHourly(samcheong, TARGET_HOUR, "40");
        insertHourly(sajik, TARGET_HOUR.minusDays(364), "50");
        insertHourly(samcheong, TARGET_HOUR.minusDays(364), "30");

        TrendResponse trend = trendService.findTrend(
                DashboardTargetType.REGION, regionId("11110"), TODAY);

        // (60 + 40) ÷ (50 + 30) = 1.25 → +25.0%
        assertThat(trend.yearRate()).isEqualByComparingTo("25.0");
        assertThat(trend.weekRate()).isNull();
        assertThat(trend.monthRate()).isNull();
    }

    @Test
    @DisplayName("FNC-C-019 API: weekRate·monthRate·yearRate, 기준 없음은 null, 없는 대상은 404")
    void api_shapeAndErrors() throws Exception {
        // 오늘(2099년이 아님)은 통계가 없어 모두 null
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekRate").isEmpty())
                .andExpect(jsonPath("$.monthRate").isEmpty())
                .andExpect(jsonPath("$.yearRate").isEmpty());
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "REGION").param("targetId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "DONG").param("targetId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
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

    private void insertHourly(long zoneId, LocalDateTime statAt, String countAvg) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec)"
                        + " VALUES (?, ?, ?, 0, 0, 0, 3600)",
                zoneId,
                Timestamp.valueOf(statAt),
                new BigDecimal(countAvg));
    }
}
