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
import java.util.stream.IntStream;

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
import com.whiteeyes.common.stat.dto.HourlyLevelItem;
import com.whiteeyes.common.stat.service.HourlyLevelService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 혼잡 단계 기준(conventions.md 기본값): 밀집도 0.7 미만 1(정상), 0.7 이상 2(주의), 1.5 이상 3(위험)
/** 시간대별 혼잡 단계(FNC-C-018) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class HourlyLevelIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String URL = "/api/v1/dashboard/hourly-levels";
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);
    // 그날 10시 30분이 '지금'이면 10시는 진행 중인 시간이다
    private static final LocalDateTime NOW = DAY.atTime(10, 30);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private HourlyLevelService hourlyLevelService;

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
        sajik = insertZone("11110530", "사직동 1구역", "100.00");
        samcheong = insertZone("11110540", "삼청동 1구역", "100.00");

        // 사직: 0시 20명(0.2 → 1단계), 5시 80명(0.8 → 2단계), 9시 받은 초 0(빈 칸)
        insertHourly(sajik, DAY.atTime(0, 0), "20", "0.2000", 3600);
        insertHourly(sajik, DAY.atTime(5, 0), "80", "0.8000", 3600);
        insertHourly(sajik, DAY.atTime(9, 0), "0", "0.0000", 0);
        // 삼청: 5시 100명(1.0)
        insertHourly(samcheong, DAY.atTime(5, 0), "100", "1.0000", 3600);
        // 10시(진행 중): 시간별 행이 있어도 쓰지 않고 1분 통계를 묶는다. 사직 1분 3개 = 150·170·160명(30초만 받은 것 1개)
        insertHourly(sajik, DAY.atTime(10, 0), "10", "0.1000", 3600);
        insertMinute(sajik, DAY.atTime(10, 0), "150", "1.5000", 60);
        insertMinute(sajik, DAY.atTime(10, 1), "170", "1.7000", 60);
        insertMinute(sajik, DAY.atTime(10, 2), "160", "1.6000", 30);
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-018 구역: 그날 시간별 밀집도를 혼잡 단계로 바꾸고, 데이터 없는 시간은 null")
    void zone_convertsHourlyDensityToLevel() {
        List<HourlyLevelItem> items = hourlyLevelService.findHourlyLevels(
                DashboardTargetType.ZONE, sajik, DAY, DAY.plusDays(1).atTime(9, 0));

        assertThat(items).hasSize(24).extracting(HourlyLevelItem::hour).containsExactlyElementsOf(range24());
        assertThat(items.get(0).density()).isEqualByComparingTo("0.2000");
        assertThat(items.get(0).level()).isEqualTo(1);
        assertThat(items.get(5).density()).isEqualByComparingTo("0.8000");
        assertThat(items.get(5).level()).isEqualTo(2);
        // 받은 초 0·통계 없음
        assertThat(items.get(9)).isEqualTo(new HourlyLevelItem(9, null, null));
        assertThat(items.get(1)).isEqualTo(new HourlyLevelItem(1, null, null));
        // 오늘이 아니면 10시도 시간별 통계 그대로
        assertThat(items.get(10).density()).isEqualByComparingTo("0.1000");
    }

    @Test
    @DisplayName("FNC-C-018 진행 중인 현재 시간은 1분 통계를 받은 초로 가중 평균해 계산하고, 그 뒤 시간은 null")
    void currentHour_usesMinuteStats() {
        List<HourlyLevelItem> items = hourlyLevelService.findHourlyLevels(DashboardTargetType.ZONE, sajik, DAY, NOW);

        // (1.5×60 + 1.7×60 + 1.6×30) ÷ 150 = 1.6 → 3단계(시간별 행의 0.1이 아님)
        assertThat(items.get(10).density()).isEqualByComparingTo("1.6000");
        assertThat(items.get(10).level()).isEqualTo(3);
        assertThat(items.subList(11, 24)).allSatisfy(item -> assertThat(item.level()).isNull());
        assertThat(items.get(5).level()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-C-018 지역은 같은 시각 소속 구역의 인원 합 ÷ 면적 합으로 합산한다")
    void region_sumsZones() {
        List<HourlyLevelItem> items = hourlyLevelService.findHourlyLevels(
                DashboardTargetType.REGION, regionId("11110"), DAY, NOW);

        // 5시: (80 + 100) ÷ (100 + 100) = 0.9 → 2단계
        assertThat(items.get(5).density()).isEqualByComparingTo("0.9000");
        assertThat(items.get(5).level()).isEqualTo(2);
        // 0시: 받은 구역(사직)만 넣는다 20 ÷ 100
        assertThat(items.get(0).density()).isEqualByComparingTo("0.2000");
        // 10시(진행 중): 1분 통계가 있는 사직만. 인원 (150×60 + 170×60 + 160×30) ÷ 150 = 160 → 160 ÷ 100
        assertThat(items.get(10).density()).isEqualByComparingTo("1.6000");
    }

    @Test
    @DisplayName("FNC-C-018 API: 24개 배열, 값이 없으면 level·density가 null, date를 빼면 오늘")
    void api_shapeAndDefaults() throws Exception {
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("date", "2099-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(24))
                .andExpect(jsonPath("$[0].hour").value(0))
                .andExpect(jsonPath("$[0].level").value(1))
                .andExpect(jsonPath("$[0].density").value(0.2))
                .andExpect(jsonPath("$[1].hour").value(1))
                .andExpect(jsonPath("$[1].level").isEmpty())
                .andExpect(jsonPath("$[1].density").isEmpty())
                .andExpect(jsonPath("$[23].hour").value(23));
        // 오늘(2099년이 아님)은 이 구역에 통계가 없어 모두 비어 있다
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(24))
                .andExpect(jsonPath("$[0].level").isEmpty());
    }

    @Test
    @DisplayName("FNC-C-018 API: 날짜·대상 형식 오류는 400 INVALID_INPUT, 없는 대상은 404")
    void api_errors() throws Exception {
        mockMvc.perform(get(URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("date", "2099-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "DONG").param("targetId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "REGION").param("targetId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mockMvc.perform(get(URL).with(AdminTestLogin.admin()).param("targetType", "ZONE").param("targetId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    private static List<Integer> range24() {
        return IntStream.range(0, 24).boxed().toList();
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private long regionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
    }

    private long insertZone(String dongCode, String name, String areaM2) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, ?, '{}', 37.600000, 127.002000)",
                regionId(dongCode),
                name,
                new BigDecimal(areaM2));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private void insertHourly(long zoneId, LocalDateTime statAt, String countAvg, String density, int sampleSec) {
        BigDecimal count = new BigDecimal(countAvg);
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec, is_demo)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                zoneId,
                Timestamp.valueOf(statAt),
                count,
                count.intValue(),
                count.intValue(),
                new BigDecimal(density),
                sampleSec);
    }

    private void insertMinute(long zoneId, LocalDateTime statAt, String countAvg, String density, int sampleSec) {
        BigDecimal count = new BigDecimal(countAvg);
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                zoneId,
                Timestamp.valueOf(statAt),
                count,
                count.intValue(),
                count.intValue(),
                new BigDecimal(density),
                sampleSec);
    }
}
