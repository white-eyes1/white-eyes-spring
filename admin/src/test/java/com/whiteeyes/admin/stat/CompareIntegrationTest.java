package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
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
import com.whiteeyes.common.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.stat.dto.CompareCell;
import com.whiteeyes.common.stat.dto.CompareRequest;
import com.whiteeyes.common.stat.dto.CompareResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.service.CompareService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 대상 기간 2099-01-05(월) ~ 2099-01-11(일), 지난달 프리셋 기준 기간 2098-12-08(월) ~ 2098-12-14(일)
/** 기간 비교 분석(FNC-C-020) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class CompareIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String COMPARE_URL = "/api/v1/compare";
    private static final LocalDateTime TARGET_MON = LocalDateTime.of(2099, 1, 5, 0, 0);
    private static final LocalDateTime BASE_MON = LocalDateTime.of(2098, 12, 8, 0, 0);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private CompareService compareService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private long sajik;
    private long deletedZone;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);

        sajik = insertZone("11110530", "사직동 1구역");
        long samcheong = insertZone("11110540", "삼청동 1구역");
        deletedZone = insertZone("11110540", "삼청동 삭제된 구역");
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-02-01 00:00:00' WHERE id = ?", deletedZone);
        long sogong = insertZone("11140520", "소공동 1구역");

        // 월 9시: 대상 60 + 40 + 20(삭제된 구역도 합산) = 120, 기준 50 + 30 = 80 → +50%
        insertHourly(sajik, TARGET_MON.withHour(9), "60", 3600);
        insertHourly(samcheong, TARGET_MON.withHour(9), "40", 3600);
        insertHourly(deletedZone, TARGET_MON.withHour(9), "20", 3600);
        insertHourly(sajik, BASE_MON.withHour(9), "50", 3600);
        insertHourly(samcheong, BASE_MON.withHour(9), "30", 3600);
        // 다른 지역(중구) 구역은 종로구 합산에 들어가지 않는다
        insertHourly(sogong, TARGET_MON.withHour(9), "999", 3600);
        // 월 10시: 받은 초가 없는 사직 행은 빼고 삼청만 → 대상 30, 기준 30 → 0%
        insertHourly(sajik, TARGET_MON.withHour(10), "100", 0);
        insertHourly(samcheong, TARGET_MON.withHour(10), "30", 3600);
        insertHourly(samcheong, BASE_MON.withHour(10), "30", 1800);
        // 일 23시(기간 마지막 날은 포함): 대상 10, 기준 20 → −50%
        insertHourly(sajik, TARGET_MON.plusDays(6).withHour(23), "10", 3600);
        insertHourly(sajik, BASE_MON.plusDays(6).withHour(23), "20", 3600);
        // 기간 다음 날(월 9시)은 빠진다
        insertHourly(sajik, TARGET_MON.plusDays(7).withHour(9), "5000", 3600);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-020 지역은 같은 시각끼리 구역(삭제 구역 포함)을 합산해 요일×시간 칸으로 비교한다")
    void regionCompare() throws Exception {
        CompareResponse response = compareService.compare(request(DashboardTargetType.REGION, regionId("11110")));

        CompareCell mon09 = cell(response, 1, 9);
        assertThat(mon09.targetAvg()).isEqualByComparingTo("120.0");
        assertThat(mon09.baseAvg()).isEqualByComparingTo("80.0");
        assertThat(mon09.rate()).isEqualByComparingTo("50.0");
        assertThat(mon09.abnormal()).isTrue();

        CompareCell mon10 = cell(response, 1, 10);
        assertThat(mon10.targetAvg()).isEqualByComparingTo("30.0");
        assertThat(mon10.rate()).isEqualByComparingTo("0.0");
        assertThat(mon10.abnormal()).isFalse();

        CompareCell sun23 = cell(response, 7, 23);
        assertThat(sun23.rate()).isEqualByComparingTo("-50.0");
        assertThat(sun23.abnormal()).isTrue();

        // (120 + 30 + 10 − (80 + 30 + 20)) ÷ (80 + 30 + 20) × 100 = 23.08
        assertThat(response.totalRate()).isEqualByComparingTo("23.1");
    }

    @Test
    @DisplayName("FNC-C-020 구역은 그 구역 값만 비교하고, 삭제된 구역은 없는 구역으로 본다")
    void zoneCompare() throws Exception {
        CompareResponse response = compareService.compare(request(DashboardTargetType.ZONE, sajik));

        // 60 vs 50 → +20%, 기준 30% 미만이라 정상
        CompareCell mon09 = cell(response, 1, 9);
        assertThat(mon09.rate()).isEqualByComparingTo("20.0");
        assertThat(mon09.abnormal()).isFalse();
        // 사직 월 10시는 받은 초가 없어 대상 없음 → 비교 불가
        assertThat(cell(response, 1, 10).targetAvg()).isNull();
        assertThat(cell(response, 1, 10).rate()).isNull();

        assertThatThrownBy(() -> compareService.compare(request(DashboardTargetType.ZONE, deletedZone)))
                .isInstanceOf(ZoneNotFoundException.class);
    }

    @Test
    @DisplayName("FNC-C-020 API는 명세의 응답 모양을 내보내고, 기간 오류는 400 INVALID_RANGE")
    void api() throws Exception {
        String body = mockMvc.perform(get(COMPARE_URL).with(AdminTestLogin.admin())
                        .param("targetType", "REGION")
                        .param("targetId", String.valueOf(regionId("11110")))
                        .param("from", "2099-01-05")
                        .param("to", "2099-01-11")
                        .param("basePreset", "LAST_MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(168))
                .andExpect(jsonPath("$.cells[9].weekday").value(1))
                .andExpect(jsonPath("$.cells[9].hour").value(9))
                .andExpect(jsonPath("$.cells[9].targetAvg").value(120.0))
                .andExpect(jsonPath("$.cells[9].baseAvg").value(80.0))
                .andExpect(jsonPath("$.cells[9].rate").value(50.0))
                .andExpect(jsonPath("$.cells[9].abnormal").value(true))
                .andExpect(jsonPath("$.totalRate").value(23.1))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        // 비교 불가 칸도 필드를 빼지 않고 null로 내보낸다(화면 JS가 rate === null로 회색 칸을 고른다)
        assertThat(body).contains(
                "{\"weekday\":1,\"hour\":0,\"targetAvg\":null,\"baseAvg\":null,\"rate\":null,\"abnormal\":false}");

        mockMvc.perform(get(COMPARE_URL).with(AdminTestLogin.admin())
                        .param("targetType", "REGION")
                        .param("targetId", String.valueOf(regionId("11110")))
                        .param("from", "2099-01-11")
                        .param("to", "2099-01-05")
                        .param("basePreset", "LAST_MONTH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(get(COMPARE_URL).with(AdminTestLogin.admin())
                        .param("targetType", "REGION")
                        .param("targetId", String.valueOf(regionId("11110")))
                        .param("from", "2099-01-05")
                        .param("to", "2099-01-11")
                        .param("basePreset", "LAST_MONTH")
                        .param("thresholdPct", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("thresholdPct"));
        mockMvc.perform(get(COMPARE_URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(deletedZone))
                        .param("from", "2099-01-05")
                        .param("to", "2099-01-11")
                        .param("basePreset", "LAST_MONTH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    private static CompareRequest request(DashboardTargetType targetType, long targetId) throws Exception {
        return CompareRequest.parse(targetType, targetId, "2099-01-05", "2099-01-11", null, null, "LAST_MONTH", null);
    }

    private static CompareCell cell(CompareResponse response, int weekday, int hour) {
        return response.cells().get((weekday - 1) * 24 + hour);
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
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
