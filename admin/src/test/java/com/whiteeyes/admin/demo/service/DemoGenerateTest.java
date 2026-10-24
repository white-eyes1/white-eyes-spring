package com.whiteeyes.admin.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.whiteeyes.admin.region.service.RegionLoadService;

// 테스트 행정구역 파일: 서울(11) → 종로구 사직동(11110530)
/** 시연용 통계 생성(FNC-A-051) */
@SpringBootTest
class DemoGenerateTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final LocalDateTime FAR_HOUR = LocalDateTime.of(2099, 1, 1, 0, 0);
    // 관리자 앱의 Clock(ClockConfig)과 같은 시간대로 어제를 구한다
    private static final LocalDate YESTERDAY = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
    private static final LocalDate START = YESTERDAY.minusDays(4);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private DemoGenerateService demoGenerateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path tempDir;

    private long cameraZoneId;
    private long noCameraZoneId;
    private long deletedZoneId;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '11110530'", Long.class);
        cameraZoneId = insertZone(dongId, "사직동 1구역", null);
        noCameraZoneId = insertZone(dongId, "사직동 2구역", null);
        deletedZoneId = insertZone(dongId, "사직동 3구역", LocalDateTime.of(2026, 1, 1, 0, 0));
        // 1구역의 동쪽 이웃이 2구역(FNC-A-016은 반대쪽도 함께 저장한다)
        insertNeighbor(cameraZoneId, "E", noCameraZoneId);
        insertNeighbor(noCameraZoneId, "W", cameraZoneId);
        // 1구역 캠은 어제 낮에 처음 가동 → 시연용은 그 전날(그제)까지
        insertCamera(cameraZoneId, YESTERDAY.atTime(13, 0));
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-A-051 일별 = 시간별 합과 일치")
    void dailyMatchesHourlySum() throws IOException {
        demoGenerateService.generate(writeConfig(validConfig()));

        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList("""
                SELECT d.zone_id, d.stat_date FROM zone_stat_daily d
                JOIN (SELECT zone_id, CAST(stat_at AS DATE) AS stat_date,
                             SUM(in_n) AS in_n, SUM(in_e) AS in_e, SUM(in_s) AS in_s, SUM(in_w) AS in_w,
                             SUM(out_n) AS out_n, SUM(out_e) AS out_e, SUM(out_s) AS out_s, SUM(out_w) AS out_w,
                             SUM(dwell_sum_sec) AS dwell_sum_sec, SUM(dwell_count) AS dwell_count,
                             SUM(sample_sec) AS sample_sec, MAX(count_max) AS count_max, MIN(count_min) AS count_min,
                             SUM(count_avg * sample_sec) AS weighted_count, SUM(density * sample_sec) AS weighted_density
                      FROM zone_stat_hourly GROUP BY zone_id, CAST(stat_at AS DATE)) h
                  ON h.zone_id = d.zone_id AND h.stat_date = d.stat_date
                WHERE d.in_n <> h.in_n OR d.in_e <> h.in_e OR d.in_s <> h.in_s OR d.in_w <> h.in_w
                   OR d.out_n <> h.out_n OR d.out_e <> h.out_e OR d.out_s <> h.out_s OR d.out_w <> h.out_w
                   OR d.dwell_sum_sec <> h.dwell_sum_sec OR d.dwell_count <> h.dwell_count
                   OR d.sample_sec <> h.sample_sec OR d.count_max <> h.count_max OR d.count_min <> h.count_min
                   OR ABS(d.count_avg - h.weighted_count / h.sample_sec) > 0.005
                   OR ABS(d.density - h.weighted_density / h.sample_sec) > 0.00005
                """);

        assertThat(count("zone_stat_daily")).isEqualTo(count("SELECT COUNT(DISTINCT zone_id || '@' || CAST(stat_at AS DATE))"
                + " FROM zone_stat_hourly"));
        assertThat(mismatches).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM zone_stat_daily WHERE sample_sec <> 86400")).isZero();
    }

    @Test
    @DisplayName("FNC-A-051 구역마다 시작일부터 캠 가동 전날(캠이 없으면 어제)까지 시간별을 만들고, 삭제된 구역과 1분 통계는 만들지 않는다")
    void generatesUntilDayBeforeFirstStart() throws IOException {
        DemoGenerateResult result = demoGenerateService.generate(writeConfig(validConfig()));

        // 1구역: 시작일 ~ 그제(4일), 2구역: 시작일 ~ 어제(5일)
        assertThat(result.zones()).isEqualTo(2);
        assertThat(result.hourly()).isEqualTo((4 + 5) * 24);
        assertThat(result.daily()).isEqualTo(4 + 5);
        assertThat(lastHour(cameraZoneId)).isEqualTo(YESTERDAY.minusDays(1).atTime(23, 0));
        assertThat(lastHour(noCameraZoneId)).isEqualTo(YESTERDAY.atTime(23, 0));
        assertThat(firstHour(noCameraZoneId)).isEqualTo(START.atStartOfDay());
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE zone_id = " + deletedZoneId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo <> 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM zone_stat_daily WHERE is_demo <> 1")).isZero();
        assertThat(count("zone_stat_minute")).isZero();
    }

    @Test
    @DisplayName("FNC-A-051 이웃 쌍은 1구역의 동쪽 유출 = 2구역의 서쪽 유입")
    void neighborFlowsMatch() throws IOException {
        demoGenerateService.generate(writeConfig(validConfig()));

        long pairedHours = count("""
                SELECT COUNT(*) FROM zone_stat_hourly a JOIN zone_stat_hourly b ON a.stat_at = b.stat_at
                WHERE a.zone_id = %d AND b.zone_id = %d AND a.out_e = b.in_w AND b.out_w = a.in_e
                """.formatted(cameraZoneId, noCameraZoneId));

        assertThat(pairedHours).isEqualTo(4 * 24);
    }

    @Test
    @DisplayName("FNC-A-051 같은 seed로 다시 실행하면 기존 시연 데이터를 지우고 같은 결과를 만들며, 실제 데이터는 남긴다")
    void rerunReplacesDemoRowsWithSameResult() throws IOException {
        insertFarHourly(true);
        insertFarHourly(false);
        Path config = writeConfig(validConfig());

        DemoGenerateResult first = demoGenerateService.generate(config);
        List<Map<String, Object>> firstRows = demoRows();
        DemoGenerateResult second = demoGenerateService.generate(config);

        assertThat(first.deleted().hourly()).isEqualTo(1);
        assertThat(second.deleted().hourly()).isEqualTo(first.hourly());
        assertThat(second.deleted().daily()).isEqualTo(first.daily());
        assertThat(demoRows()).isEqualTo(firstRows);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 0")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE stat_at = '2099-01-01 00:00:00'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 설정이 틀리면 기존 시연 데이터를 지우지 않고 멈춘다")
    void invalidConfigKeepsExistingRows() throws IOException {
        insertFarHourly(true);
        Path noSeed = writeConfig(validConfig().replace("\"seed\": 42,", ""));
        Path shortHours = writeConfig(validConfig().replace("\"hourWeights\": [1,", "\"hourWeights\": ["));
        Path typo = writeConfig(validConfig().replace("\"seed\"", "\"sead\""));

        assertThatThrownBy(() -> demoGenerateService.generate(noSeed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> demoGenerateService.generate(shortHours))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> demoGenerateService.generate(typo)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 설정이 없는 구역이 있고 defaultZone도 없으면 기존 시연 데이터를 지우지 않고 멈춘다")
    void zoneWithoutSettingStops() throws IOException {
        insertFarHourly(true);
        String onlyFirstZone = validConfig()
                .replace("\"defaultZone\": {\"baseCount\": 20, \"avgDwellSec\": 300},", "");
        Path config = writeConfig(onlyFirstZone);

        assertThatThrownBy(() -> demoGenerateService.generate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zoneId=" + noCameraZoneId);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 구역별 기본 인원을 따른다(가중치가 모두 1이면 평균 인원은 기본 인원의 ±10%)")
    void followsZoneBaseCount() throws IOException {
        demoGenerateService.generate(writeConfig(validConfig()));

        BigDecimal min = jdbcTemplate.queryForObject(
                "SELECT MIN(count_avg) FROM zone_stat_hourly WHERE zone_id = ?", BigDecimal.class, cameraZoneId);
        BigDecimal max = jdbcTemplate.queryForObject(
                "SELECT MAX(count_avg) FROM zone_stat_hourly WHERE zone_id = ?", BigDecimal.class, cameraZoneId);
        BigDecimal density = jdbcTemplate.queryForObject(
                "SELECT density FROM zone_stat_hourly WHERE zone_id = ? AND stat_at = ?",
                BigDecimal.class,
                cameraZoneId,
                Timestamp.valueOf(START.atStartOfDay()));
        BigDecimal avg = jdbcTemplate.queryForObject(
                "SELECT count_avg FROM zone_stat_hourly WHERE zone_id = ? AND stat_at = ?",
                BigDecimal.class,
                cameraZoneId,
                Timestamp.valueOf(START.atStartOfDay()));

        assertThat(min.doubleValue()).isGreaterThanOrEqualTo(45.0);
        assertThat(max.doubleValue()).isLessThanOrEqualTo(55.0);
        assertThat(density).isEqualByComparingTo(avg.divide(new BigDecimal("100.00"), 4, RoundingMode.HALF_UP));
    }

    // 가중치를 모두 1로 두고 1구역만 따로 정한다(2구역은 defaultZone)
    private String validConfig() {
        return """
                {
                  "seed": 42,
                  "startDate": "%s",
                  "defaultZone": {"baseCount": 20, "avgDwellSec": 300},
                  "zones": [{"zoneId": %d, "baseCount": 50, "avgDwellSec": 600}],
                  "hourWeights": [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
                  "dayOfWeekWeights": {"MONDAY": 1, "TUESDAY": 1, "WEDNESDAY": 1, "THURSDAY": 1,
                                       "FRIDAY": 1, "SATURDAY": 1, "SUNDAY": 1},
                  "seasonWeights": {"SPRING": 1, "SUMMER": 1, "AUTUMN": 1, "WINTER": 1},
                  "surgeDays": []
                }
                """.formatted(START, cameraZoneId);
    }

    private Path writeConfig(String content) throws IOException {
        Path file = Files.createTempFile(tempDir, "generate-demo", ".json");
        Files.writeString(file, content);
        return file;
    }

    private List<Map<String, Object>> demoRows() {
        return jdbcTemplate.queryForList("SELECT * FROM zone_stat_hourly WHERE is_demo = 1 ORDER BY zone_id, stat_at");
    }

    private LocalDateTime lastHour(long zoneId) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(stat_at) FROM zone_stat_hourly WHERE zone_id = ?", LocalDateTime.class, zoneId);
    }

    private LocalDateTime firstHour(long zoneId) {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(stat_at) FROM zone_stat_hourly WHERE zone_id = ?", LocalDateTime.class, zoneId);
    }

    // 표 이름만 주면 전체 건수, SELECT로 시작하면 그 결과
    private long count(String tableOrQuery) {
        String sql = tableOrQuery.startsWith("SELECT") ? tableOrQuery : "SELECT COUNT(*) FROM " + tableOrQuery;
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private long insertZone(long dongId, String name, LocalDateTime deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng, deleted_at)"
                        + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000, ?)",
                dongId,
                name,
                deletedAt == null ? null : Timestamp.valueOf(deletedAt));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private void insertNeighbor(long zoneId, String direction, long neighborId) {
        jdbcTemplate.update(
                "INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, ?, ?)",
                zoneId,
                direction,
                neighborId);
    }

    private void insertCamera(long zoneId, LocalDateTime firstStartedAt) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, first_started_at)"
                        + " VALUES (?, '사직동 캠', 'DUMMY', 'videos/a.mp4', 0, 'STOPPED', 0, ?)",
                zoneId,
                Timestamp.valueOf(firstStartedAt));
    }

    // 생성 범위와 겹치지 않는 먼 미래 시각에 한 행
    private void insertFarHourly(boolean demo) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec, is_demo)"
                        + " VALUES (?, ?, 10.00, 0, 0, 0, 3600, ?)",
                demo ? cameraZoneId : noCameraZoneId,
                Timestamp.valueOf(demo ? FAR_HOUR.plusHours(1) : FAR_HOUR),
                demo ? 1 : 0);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 행을 지우고 끝낸다(FK)
    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM zone_stat_daily");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone");
    }
}
