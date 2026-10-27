package com.whiteeyes.admin.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.whiteeyes.admin.demo.repository.DemoDailyStat;
import com.whiteeyes.admin.demo.repository.DemoStatRepository;
import com.whiteeyes.admin.region.service.RegionLoadService;

// 테스트 행정구역 파일: 서울(11) → 종로구 사직동(11110530)
/** 시연용 통계 생성(FNC-A-051) */
@SpringBootTest
class DemoGenerateTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 실행 시각 2026-10-21 14:20 → 캠이 없는 구역의 끝 시각은 14시(13시까지 만든다)
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 14, 20);
    private static final LocalDate TODAY = NOW.toLocalDate();
    // 1구역 캠의 첫 가동 2026-10-19 13:37 → 1구역의 끝 시각은 그날 13시(12시까지 만든다)
    private static final LocalDateTime FIRST_STARTED_AT = LocalDateTime.of(2026, 10, 19, 13, 37);
    private static final LocalDate START_DAY = FIRST_STARTED_AT.toLocalDate();
    // 1구역: 2025-10-19 0시 ~ 2026-10-19 13시, 2구역: 2025-10-21 0시 ~ 2026-10-21 14시(1년 = 365일)
    private static final int CAMERA_ZONE_HOURS = 365 * 24 + 13;
    private static final int NO_CAMERA_ZONE_HOURS = 365 * 24 + 14;
    // 1구역: 꽉 찬 365일 + 첫 가동일(시연용 0~12시를 일별 집계로 묶음), 2구역: 꽉 찬 365일(오늘은 자정 집계가 만듦)
    private static final int CAMERA_ZONE_DAYS = 365 + 1;
    private static final int NO_CAMERA_ZONE_DAYS = 365;
    private static final LocalDateTime FAR_HOUR = LocalDateTime.of(2099, 1, 1, 0, 0);

    @TestConfiguration
    static class FixedClockConfig {

        // 끝 시각이 실행 시각에 따라 바뀌지 않게 현재 시각을 고정한다
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private DemoGenerateService demoGenerateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 일별 저장 실패를 일으키려고 감싼다. 따로 정하지 않으면 실제 저장을 그대로 부른다
    @MockitoSpyBean
    private DemoStatRepository demoStatRepository;

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
        insertCamera(cameraZoneId, FIRST_STARTED_AT);
    }

    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-A-051 일별 = 시간별 합과 일치")
    void dailyMatchesHourlySum() throws IOException {
        demoGenerateService.generate(writeConfig(validConfig()), null);

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

        // 시간별이 있는 (구역, 날짜) 중 2구역의 오늘만 일별이 없다
        assertThat(count("zone_stat_daily")).isEqualTo(count("SELECT COUNT(DISTINCT zone_id || '@' || CAST(stat_at AS DATE))"
                + " FROM zone_stat_hourly") - 1);
        assertThat(mismatches).isEmpty();
        // 24시간이 안 되는 일별은 1구역의 첫 가동일(0~12시) 하나뿐
        assertThat(jdbcTemplate.queryForList(
                        "SELECT zone_id, stat_date, sample_sec FROM zone_stat_daily WHERE sample_sec <> 86400"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(((Number) row.get("zone_id")).longValue()).isEqualTo(cameraZoneId);
                    assertThat(row.get("stat_date").toString()).isEqualTo(START_DAY.toString());
                    assertThat(((Number) row.get("sample_sec")).intValue()).isEqualTo(13 * 3600);
                });
    }

    @Test
    @DisplayName("FNC-A-051 구역마다 끝 시각(첫 가동이 속한 정시, 캠이 없으면 실행 시각의 정시) 직전까지, 그 1년 전 날짜 0시부터 만든다")
    void generatesUntilHourBeforeEnd() throws IOException {
        DemoGenerateResult result = demoGenerateService.generate(writeConfig(validConfig()), null);

        assertThat(result.zones()).isEqualTo(2);
        assertThat(result.hourly()).isEqualTo(CAMERA_ZONE_HOURS + NO_CAMERA_ZONE_HOURS);
        assertThat(result.daily()).isEqualTo(CAMERA_ZONE_DAYS + NO_CAMERA_ZONE_DAYS);
        assertThat(firstHour(cameraZoneId)).isEqualTo(LocalDateTime.of(2025, 10, 19, 0, 0));
        assertThat(lastHour(cameraZoneId)).isEqualTo(START_DAY.atTime(12, 0));
        assertThat(firstHour(noCameraZoneId)).isEqualTo(LocalDateTime.of(2025, 10, 21, 0, 0));
        assertThat(lastHour(noCameraZoneId)).isEqualTo(TODAY.atTime(13, 0));
        assertThat(count("SELECT COUNT(*) FROM zone_stat_daily WHERE stat_date = '" + TODAY + "'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE zone_id = " + deletedZoneId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo <> 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM zone_stat_daily WHERE is_demo <> 1")).isZero();
        assertThat(count("zone_stat_minute")).isZero();
    }

    @Test
    @DisplayName("FNC-A-051 설정 파일이 없으면 기본 설정으로 만든다")
    void generatesWithoutConfigFile() {
        DemoGenerateResult result = demoGenerateService.generate(null, null);

        assertThat(result.zones()).isEqualTo(2);
        assertThat(result.hourly()).isEqualTo(CAMERA_ZONE_HOURS + NO_CAMERA_ZONE_HOURS);
    }

    @Test
    @DisplayName("FNC-A-051 이웃 쌍은 1구역의 동쪽 유출 = 2구역의 서쪽 유입")
    void neighborFlowsMatch() throws IOException {
        demoGenerateService.generate(writeConfig(validConfig()), null);

        // 두 구역이 모두 시연용인 시각: 2025-10-21 0시 ~ 2026-10-19 13시(363일 + 13시간)
        assertThat(pairedHours()).isEqualTo(363 * 24 + 13);
    }

    @Test
    @DisplayName("FNC-A-051 --zone이면 그 구역의 시연용 행만 지우고 다시 만들며, 다른 구역과 실제 데이터는 그대로이고 이웃 짝도 맞는다")
    void zoneOptionRegeneratesOnlyThatZone() throws IOException {
        insertFarHourly(false);
        Path config = writeConfig(validConfig());
        demoGenerateService.generate(config, null);
        List<Map<String, Object>> before = demoRows();

        DemoGenerateResult result = demoGenerateService.generate(config, List.of(noCameraZoneId));

        assertThat(result.zones()).isEqualTo(1);
        assertThat(result.deleted().hourly()).isEqualTo(NO_CAMERA_ZONE_HOURS);
        assertThat(result.deleted().daily()).isEqualTo(NO_CAMERA_ZONE_DAYS);
        assertThat(result.hourly()).isEqualTo(NO_CAMERA_ZONE_HOURS);
        assertThat(demoRows()).isEqualTo(before);
        assertThat(pairedHours()).isEqualTo(363 * 24 + 13);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 0")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 --zone에 없거나 삭제된 구역이 있으면 기존 시연 데이터를 지우지 않고 멈춘다")
    void unknownZoneStops() throws IOException {
        insertFarHourly(true);
        Path config = writeConfig(validConfig());

        assertThatThrownBy(() -> demoGenerateService.generate(config, List.of(noCameraZoneId, deletedZoneId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(deletedZoneId));
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 첫 가동일의 일별이 이미 있으면(실제, is_demo=0) 건드리지 않는다")
    void keepsExistingRealDailyOfFirstStartDay() throws IOException {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_daily (zone_id, stat_date, count_avg, count_max, count_min, density, sample_sec, is_demo)"
                        + " VALUES (?, ?, 7.00, 9, 5, 0, 39600, 0)",
                cameraZoneId,
                Date.valueOf(START_DAY));

        DemoGenerateResult result = demoGenerateService.generate(writeConfig(validConfig()), null);

        assertThat(result.daily()).isEqualTo(CAMERA_ZONE_DAYS - 1 + NO_CAMERA_ZONE_DAYS);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT count_avg, sample_sec, is_demo FROM zone_stat_daily WHERE zone_id = ? AND stat_date = ?",
                        cameraZoneId,
                        Date.valueOf(START_DAY)))
                .satisfies(row -> {
                    assertThat((BigDecimal) row.get("count_avg")).isEqualByComparingTo("7.00");
                    assertThat(((Number) row.get("sample_sec")).intValue()).isEqualTo(39600);
                    assertThat(row.get("is_demo")).isIn(false, 0, (byte) 0);
                });
    }

    @Test
    @DisplayName("FNC-A-051 같은 seed로 다시 실행하면 기존 시연 데이터를 지우고 같은 결과를 만들며, 실제 데이터는 남긴다")
    void rerunReplacesDemoRowsWithSameResult() throws IOException {
        insertFarHourly(true);
        insertFarHourly(false);
        Path config = writeConfig(validConfig());

        DemoGenerateResult first = demoGenerateService.generate(config, null);
        List<Map<String, Object>> firstRows = demoRows();
        DemoGenerateResult second = demoGenerateService.generate(config, null);

        assertThat(first.deleted().hourly()).isEqualTo(1);
        assertThat(second.deleted().hourly()).isEqualTo(first.hourly());
        assertThat(second.deleted().daily()).isEqualTo(first.daily());
        assertThat(demoRows()).isEqualTo(firstRows);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 0")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE stat_at = '2099-01-01 00:00:00'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 같은 날의 일별 저장이 실패하면 그날 시간별도 남지 않고, 앞선 날은 일별 = 시간별 그대로 남는다")
    void dailyFailureRollsBackSameDayHourly() throws IOException {
        // 2025-10-19·20은 1구역만, 21일부터 두 구역
        LocalDate failDate = LocalDate.of(2025, 10, 21);
        doAnswer(invocation -> {
                    List<DemoDailyStat> rows = invocation.getArgument(0);
                    if (!rows.isEmpty() && rows.get(0).statDate().equals(failDate)) {
                        throw new IllegalStateException("일별 저장 실패(시험)");
                    }
                    return invocation.callRealMethod();
                })
                .when(demoStatRepository)
                .insertDaily(anyList());
        Path config = writeConfig(validConfig());

        assertThatThrownBy(() -> demoGenerateService.generate(config, null)).hasMessageContaining("일별 저장 실패(시험)");

        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly")).isEqualTo(2 * 24);
        assertThat(count("zone_stat_daily")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE stat_at >= '" + failDate + " 00:00:00'"))
                .isZero();
    }

    @Test
    @DisplayName("FNC-A-051 설정이 틀리면 기존 시연 데이터를 지우지 않고 멈춘다")
    void invalidConfigKeepsExistingRows() throws IOException {
        insertFarHourly(true);
        Path shortHours = writeConfig(validConfig().replace("\"hourWeights\": [1,", "\"hourWeights\": ["));
        Path typo = writeConfig(validConfig().replace("\"seed\"", "\"sead\""));

        assertThatThrownBy(() -> demoGenerateService.generate(shortHours, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> demoGenerateService.generate(typo, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("SELECT COUNT(*) FROM zone_stat_hourly WHERE is_demo = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-051 구역별 기본 인원을 따른다(가중치가 모두 1이면 평균 인원은 기본 인원의 ±10%)")
    void followsZoneBaseCount() throws IOException {
        demoGenerateService.generate(writeConfig(validConfig()), null);
        Timestamp firstHour = Timestamp.valueOf(LocalDateTime.of(2025, 10, 19, 0, 0));

        BigDecimal min = jdbcTemplate.queryForObject(
                "SELECT MIN(count_avg) FROM zone_stat_hourly WHERE zone_id = ?", BigDecimal.class, cameraZoneId);
        BigDecimal max = jdbcTemplate.queryForObject(
                "SELECT MAX(count_avg) FROM zone_stat_hourly WHERE zone_id = ?", BigDecimal.class, cameraZoneId);
        BigDecimal density = jdbcTemplate.queryForObject(
                "SELECT density FROM zone_stat_hourly WHERE zone_id = ? AND stat_at = ?",
                BigDecimal.class,
                cameraZoneId,
                firstHour);
        BigDecimal avg = jdbcTemplate.queryForObject(
                "SELECT count_avg FROM zone_stat_hourly WHERE zone_id = ? AND stat_at = ?",
                BigDecimal.class,
                cameraZoneId,
                firstHour);

        assertThat(min.doubleValue()).isGreaterThanOrEqualTo(45.0);
        assertThat(max.doubleValue()).isLessThanOrEqualTo(55.0);
        assertThat(density).isEqualByComparingTo(avg.divide(new BigDecimal("100.00"), 4, RoundingMode.HALF_UP));
    }

    // 가중치를 모두 1로 두고 1구역만 따로 정한다(2구역은 defaultZone)
    private String validConfig() {
        return """
                {
                  "seed": 42,
                  "defaultZone": {"baseCount": 20, "avgDwellSec": 300},
                  "zones": [{"zoneId": %d, "baseCount": 50, "avgDwellSec": 600}],
                  "hourWeights": [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
                  "dayOfWeekWeights": {"MONDAY": 1, "TUESDAY": 1, "WEDNESDAY": 1, "THURSDAY": 1,
                                       "FRIDAY": 1, "SATURDAY": 1, "SUNDAY": 1},
                  "seasonWeights": {"SPRING": 1, "SUMMER": 1, "AUTUMN": 1, "WINTER": 1},
                  "surgeDays": []
                }
                """.formatted(cameraZoneId);
    }

    private Path writeConfig(String content) throws IOException {
        Path file = Files.createTempFile(tempDir, "generate-demo", ".json");
        Files.writeString(file, content);
        return file;
    }

    // 1구역의 동쪽 유출 = 2구역의 서쪽 유입이고 반대도 맞는 시각 수
    private long pairedHours() {
        return count("""
                SELECT COUNT(*) FROM zone_stat_hourly a JOIN zone_stat_hourly b ON a.stat_at = b.stat_at
                WHERE a.zone_id = %d AND b.zone_id = %d AND a.out_e = b.in_w AND b.out_w = a.in_e
                """.formatted(cameraZoneId, noCameraZoneId));
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
