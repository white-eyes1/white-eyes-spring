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
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.PeakHourItem;
import com.whiteeyes.common.stat.dto.PeakPeriod;
import com.whiteeyes.common.stat.service.PeakHourService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 오늘 2099-01-15 → WEEK = 01-08 ~ 01-14, DAY = 01-14. 기대값은 아래 시간별 통계로 손으로 계산했다
/** 피크 타임 TOP 3(FNC-C-029) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class PeakHourIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String URL = "/api/v1/dashboard/peak-hours";
    private static final LocalDate TODAY = LocalDate.of(2099, 1, 15);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private PeakHourService peakHourService;

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

        // 사직: 18시 그제 100·어제 120(평균 110), 9시 어제 110, 12시 어제 50, 7시 어제 40
        insertHourly(sajik, YESTERDAY.minusDays(1).atTime(18, 0), "100", 3600);
        insertHourly(sajik, YESTERDAY.atTime(18, 0), "120", 3600);
        insertHourly(sajik, YESTERDAY.atTime(9, 0), "110", 3600);
        insertHourly(sajik, YESTERDAY.atTime(12, 0), "50", 3600);
        insertHourly(sajik, YESTERDAY.atTime(7, 0), "40", 3600);
        // 받은 초 0은 넣지 않는다, 오늘은 기간 밖, 8일 전은 WEEK 밖(MONTH 안)
        insertHourly(sajik, YESTERDAY.atTime(3, 0), "999", 0);
        insertHourly(sajik, TODAY.atTime(18, 0), "999", 3600);
        insertHourly(sajik, TODAY.minusDays(8).atTime(20, 0), "999", 3600);
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-029 WEEK: 시별 평균 인원 내림차순 상위 3개, 같으면 이른 시각 먼저")
    void week_topThreeWithTieBreak() {
        List<PeakHourItem> items = peakHourService.findPeakHours(DashboardTargetType.ZONE, sajik, PeakPeriod.WEEK, TODAY);

        // 9시 110 = 18시 (100 + 120) ÷ 2 = 110 → 이른 9시가 1위
        assertThat(items).containsExactly(
                new PeakHourItem(1, 9, new BigDecimal("110.0")),
                new PeakHourItem(2, 18, new BigDecimal("110.0")),
                new PeakHourItem(3, 12, new BigDecimal("50.0")));
    }

    @Test
    @DisplayName("FNC-C-029 기간: DAY는 어제, MONTH는 어제까지 28일")
    void periods() {
        List<PeakHourItem> day = peakHourService.findPeakHours(DashboardTargetType.ZONE, sajik, PeakPeriod.DAY, TODAY);
        List<PeakHourItem> month =
                peakHourService.findPeakHours(DashboardTargetType.ZONE, sajik, PeakPeriod.MONTH, TODAY);

        assertThat(day).extracting(PeakHourItem::hour).containsExactly(18, 9, 12);
        assertThat(day.get(0).avgCount()).isEqualByComparingTo("120.0");
        assertThat(month.get(0)).isEqualTo(new PeakHourItem(1, 20, new BigDecimal("999.0")));
    }

    @Test
    @DisplayName("FNC-C-029 지역은 같은 시각 소속 구역의 인원을 더한 뒤 평균하고, 데이터가 없으면 빈 목록")
    void region_sumsZones_andEmptyWithoutData() {
        insertHourly(samcheong, YESTERDAY.atTime(12, 0), "100", 3600);

        List<PeakHourItem> region = peakHourService.findPeakHours(
                DashboardTargetType.REGION, regionId("11110"), PeakPeriod.DAY, TODAY);
        List<PeakHourItem> empty = peakHourService.findPeakHours(
                DashboardTargetType.REGION, regionId("11140"), PeakPeriod.WEEK, TODAY);

        // 12시: 50 + 100 = 150 → 1위
        assertThat(region.get(0)).isEqualTo(new PeakHourItem(1, 12, new BigDecimal("150.0")));
        assertThat(empty).isEmpty();
    }

    @Test
    @DisplayName("FNC-C-029 API: period를 비우면 WEEK, 형식 오류는 400 INVALID_INPUT, 없는 대상은 404")
    void api_defaultsAndErrors() throws Exception {
        // 오늘(2099년이 아님)은 통계가 없어 빈 목록
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("period", "YEAR"))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("period", "QUARTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "ZONE").param("targetId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
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
