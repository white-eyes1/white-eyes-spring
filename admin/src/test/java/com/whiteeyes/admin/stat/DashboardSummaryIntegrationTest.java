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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
import com.whiteeyes.common.stat.dto.DashboardSummaryResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.service.DashboardSummaryService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 값은 서비스에 고정 시각(NOW)을 넘겨 확인하고, HTTP 형식·오류는 MockMvc로 확인한다
/** 현재 현황 요약(FNC-C-016) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardSummaryIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SUMMARY_URL = "/api/v1/dashboard/summary";
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 12, 0, 30);
    private static final LocalDateTime LATEST = LocalDateTime.of(2099, 1, 1, 11, 59);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private DashboardSummaryService dashboardSummaryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private long sajik;
    private long samcheong;
    private long lagging;
    private long deletedZone;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);

        sajik = insertZone("11110530", "사직동 1구역", "100.00");
        samcheong = insertZone("11110540", "삼청동 1구역", "300.00");
        lagging = insertZone("11110540", "삼청동 2구역", "1000.00");
        deletedZone = insertZone("11110540", "삼청동 삭제된 구역", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-01 10:00:00' WHERE id = ?", deletedZone);

        // 최근 1분(11:59): 사직 50명(유입 N1·E2, 유출 E3·S3), 삼청 30명(유입 S4, 유출 N1·W2)
        insertStat(sajik, LATEST, "50.00", "0.5000", 60, new int[] {1, 2, 0, 0}, new int[] {0, 3, 3, 0});
        insertStat(samcheong, LATEST, "30.00", "0.1000", 40, new int[] {0, 0, 4, 0}, new int[] {1, 0, 0, 2});
        // 캠이 끊겨 1분 전 통계만 있는 구역: 현재 합산에서 빠지고 오늘 최대 인원에는 들어간다
        insertStat(lagging, LATEST.minusMinutes(1), "800.00", "0.8000", 900, new int[4], new int[4]);
        // 오늘 10시에 삭제된 구역의 오전 기록: 오늘 최대 인원에 들어간다
        insertStat(deletedZone, LocalDateTime.of(2099, 1, 1, 9, 0), "900.00", "9.0000", 1000, new int[4], new int[4]);
        // 어제 기록은 오늘 최대 인원에서 빠진다
        insertStat(sajik, LocalDateTime.of(2098, 12, 31, 23, 59), "50.00", "0.5000", 5000, new int[4], new int[4]);

        insertCamera(sajik, "RUNNING", false);
        insertCamera(samcheong, "STOPPED", false);
        insertCamera(lagging, "RUNNING", true);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·캠·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-016 지역은 가장 늦은 1분의 구역 값을 합산하고, 캠 수와 오늘 최대 인원(삭제 구역 포함)을 함께 준다")
    void regionSummary() {
        DashboardSummaryResponse summary =
                dashboardSummaryService.findSummary(DashboardTargetType.REGION, regionIdLong("11110"), NOW);

        assertThat(summary.statAt()).isEqualTo(LATEST.atOffset(ZoneOffset.ofHours(9)));
        assertThat(summary.countAvg()).isEqualByComparingTo("80.00");
        // (50 + 30) ÷ (100 + 300)
        assertThat(summary.density()).isEqualByComparingTo("0.2000");
        assertThat(summary.level()).isEqualTo(1);
        assertThat(summary.inflow()).isEqualTo(7);
        assertThat(summary.outflow()).isEqualTo(9);
        // 유출 N1·E3·S3·W2: E와 S가 같아 앞의 E
        assertThat(summary.mainDirection()).isEqualTo("E");
        assertThat(summary.todayEventCount()).isZero();
        // 삭제되지 않은 캠 2대(사직 RUNNING, 삼청 STOPPED). 삼청 2구역의 캠은 삭제됨
        assertThat(summary.cameraCount()).isEqualTo(2);
        assertThat(summary.runningCameraCount()).isEqualTo(1);
        // 시각별 count_max 합: 11:59 = 60+40, 11:58 = 900, 09:00 = 1000(삭제된 구역), 어제 5000은 제외
        assertThat(summary.todayMaxCount()).isEqualTo(1000);
    }

    @Test
    @DisplayName("FNC-C-016 구역은 그 구역의 저장된 값과 캠 수, 오늘 최대 인원을 준다")
    void zoneSummary() {
        DashboardSummaryResponse summary = dashboardSummaryService.findSummary(DashboardTargetType.ZONE, sajik, NOW);

        assertThat(summary.countAvg()).isEqualByComparingTo("50.00");
        assertThat(summary.density()).isEqualByComparingTo("0.5000");
        assertThat(summary.inflow()).isEqualTo(3);
        assertThat(summary.outflow()).isEqualTo(6);
        assertThat(summary.mainDirection()).isEqualTo("E");
        assertThat(summary.cameraCount()).isEqualTo(1);
        assertThat(summary.runningCameraCount()).isEqualTo(1);
        assertThat(summary.todayMaxCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("FNC-C-016 최근 5분 안에 통계가 없으면 데이터 없음, 유출이 없으면 주요 방향 없음")
    void noDataAndNoOutflow() {
        DashboardSummaryResponse noData = dashboardSummaryService.findSummary(
                DashboardTargetType.REGION, regionIdLong("11140"), NOW);
        assertThat(noData.statAt()).isNull();
        assertThat(noData.countAvg()).isNull();
        assertThat(noData.level()).isNull();
        assertThat(noData.mainDirection()).isNull();
        assertThat(noData.cameraCount()).isZero();
        assertThat(noData.todayMaxCount()).isNull();

        // 1분 늦은 통계만 있는 구역 하나: 유출이 0이라 방향 없음
        DashboardSummaryResponse noOutflow = dashboardSummaryService.findSummary(DashboardTargetType.ZONE, lagging, NOW);
        assertThat(noOutflow.countAvg()).isEqualByComparingTo("800.00");
        assertThat(noOutflow.outflow()).isZero();
        assertThat(noOutflow.mainDirection()).isNull();
    }

    @Test
    @DisplayName("FNC-C-016 삭제된 구역은 없는 구역으로 본다")
    void deletedZone_notFound() {
        assertThatThrownBy(() -> dashboardSummaryService.findSummary(DashboardTargetType.ZONE, deletedZone, NOW))
                .isInstanceOf(ZoneNotFoundException.class);
    }

    @Test
    @DisplayName("FNC-C-016 API는 명세의 필드를 모두 내보내고, 없는 지역·구역은 404, 형식 오류는 400")
    void api() throws Exception {
        String body = mockMvc.perform(get(SUMMARY_URL).with(AdminTestLogin.admin()).param("targetType", "REGION").param("targetId", regionId("11140")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cameraCount").value(0))
                .andExpect(jsonPath("$.todayEventCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains(
                "\"statAt\":null", "\"countAvg\":null", "\"density\":null", "\"level\":null", "\"inflow\":null",
                "\"outflow\":null", "\"mainDirection\":null", "\"runningCameraCount\":0", "\"todayMaxCount\":null");

        mockMvc.perform(get(SUMMARY_URL).with(AdminTestLogin.admin()).param("targetType", "REGION").param("targetId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mockMvc.perform(get(SUMMARY_URL).with(AdminTestLogin.admin()).param("targetType", "ZONE").param("targetId", String.valueOf(deletedZone)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
        mockMvc.perform(get(SUMMARY_URL).with(AdminTestLogin.admin()).param("targetType", "DONG").param("targetId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get(SUMMARY_URL).with(AdminTestLogin.admin()).param("targetType", "ZONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-C-016 지역은 지역 안 구역끼리 주고받은 이동을 빼고(stats-008) 구역은 그대로 센다")
    void regionSummary_excludesMovesBetweenZonesInRegion() {
        // 사직 1구역의 동쪽 = 삼청 1구역(삼청의 서쪽 = 사직). 둘 다 종로구 안
        jdbcTemplate.update("INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, 'E', ?)", sajik, samcheong);
        jdbcTemplate.update("INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, 'W', ?)", samcheong, sajik);

        DashboardSummaryResponse jongno =
                dashboardSummaryService.findSummary(DashboardTargetType.REGION, regionIdLong("11110"), NOW);
        // 유입: 사직 N1·E2(빠짐)·S0·W0 = 1, 삼청 S4 = 4 → 5. 유출: 사직 E3(빠짐)·S3 = 3, 삼청 N1·W2(빠짐) = 1 → 4
        assertThat(jongno.inflow()).isEqualTo(5);
        assertThat(jongno.outflow()).isEqualTo(4);
        // 남은 유출 N1·S3 → S
        assertThat(jongno.mainDirection()).isEqualTo("S");

        // 이웃이 지역 밖(삼청동)인 사직동만 보면 그 방향은 지역 밖으로 나간 이동이라 그대로 센다
        DashboardSummaryResponse sajikDong =
                dashboardSummaryService.findSummary(DashboardTargetType.REGION, regionIdLong("11110530"), NOW);
        assertThat(sajikDong.inflow()).isEqualTo(3);
        assertThat(sajikDong.outflow()).isEqualTo(6);

        // 구역 하나는 이웃이 있어도 그대로 센다
        DashboardSummaryResponse zone = dashboardSummaryService.findSummary(DashboardTargetType.ZONE, sajik, NOW);
        assertThat(zone.inflow()).isEqualTo(3);
        assertThat(zone.outflow()).isEqualTo(6);
    }

    @Test
    @DisplayName("FNC-C-017 오늘 시작한 혼잡·급증만 세고 캠 이상·어제 것은 빼며, 지역은 삭제된 구역까지 합한다")
    void todayEventCount_countsCongestionAndSurgeOfToday() {
        LocalDateTime todayStart = NOW.toLocalDate().atStartOfDay();
        long otherRegionZone = insertZone("11140520", "소공동 1구역", "100.00");
        insertEvent(sajik, "CONGESTION", todayStart.plusHours(8));
        insertEvent(sajik, "SURGE", todayStart);
        insertEvent(sajik, "CAM_FAULT", todayStart.plusHours(9));
        insertEvent(sajik, "CONGESTION", todayStart.minusSeconds(1));
        insertEvent(samcheong, "SURGE", todayStart.plusHours(10));
        insertEvent(deletedZone, "CONGESTION", todayStart.plusHours(7));
        insertEvent(otherRegionZone, "CONGESTION", todayStart.plusHours(6));

        // 사직 2(오늘 00:00 정각 포함, 캠 이상·어제 제외) + 삼청 1 + 삭제된 구역 1
        assertThat(dashboardSummaryService
                        .findSummary(DashboardTargetType.REGION, regionIdLong("11110"), NOW)
                        .todayEventCount())
                .isEqualTo(4);
        assertThat(dashboardSummaryService
                        .findSummary(DashboardTargetType.ZONE, sajik, NOW)
                        .todayEventCount())
                .isEqualTo(2);
        // 5분 안에 통계가 없는 지역도 이벤트 수는 센다
        DashboardSummaryResponse noStat =
                dashboardSummaryService.findSummary(DashboardTargetType.REGION, regionIdLong("11140"), NOW);
        assertThat(noStat.statAt()).isNull();
        assertThat(noStat.todayEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-C-017 API 응답에 todayEventCount가 실제 값으로 나온다")
    void todayEventCount_inApiResponse() throws Exception {
        insertEvent(sajik, "CONGESTION", LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay().plusSeconds(1));
        insertEvent(sajik, "CAM_FAULT", LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay().plusSeconds(1));

        mockMvc.perform(get(SUMMARY_URL).with(AdminTestLogin.admin()).param("targetType", "ZONE").param("targetId", String.valueOf(sajik)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayEventCount").value(1));
    }

    private void insertEvent(long zoneId, String type, LocalDateTime startedAt) {
        jdbcTemplate.update(
                "INSERT INTO event (type, zone_id, started_at, threshold_snapshot) VALUES (?, ?, ?, ?)",
                type,
                zoneId,
                Timestamp.valueOf(startedAt),
                "{\"warnDensity\":0.7,\"dangerDensity\":1.5}");
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private String regionId(String code) {
        return String.valueOf(jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code));
    }

    private long regionIdLong(String code) {
        return Long.parseLong(regionId(code));
    }

    // 구역 등록 API로 만들면 면적이 다각형에서 계산되므로, 계산하기 쉬운 면적을 두려고 직접 넣는다
    private long insertZone(String dongCode, String name, String areaM2) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, ?, '{}', 37.600000, 127.002000)",
                regionIdLong(dongCode),
                name,
                new BigDecimal(areaM2));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private void insertCamera(long zoneId, String status, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status, deleted_at)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', ?, ?)",
                zoneId,
                status,
                deleted ? Timestamp.valueOf(LocalDateTime.of(2099, 1, 1, 10, 0)) : null);
    }

    // in·out은 N·E·S·W 순서
    private void insertStat(
            long zoneId, LocalDateTime statAt, String countAvg, String density, int countMax, int[] in, int[] out) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, sample_sec)"
                        + " VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, 60)",
                zoneId,
                Timestamp.valueOf(statAt),
                new BigDecimal(countAvg),
                countMax,
                new BigDecimal(density),
                in[0], in[1], in[2], in[3],
                out[0], out[1], out[2], out[3]);
    }
}
