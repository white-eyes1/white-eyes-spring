package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
import com.whiteeyes.admin.stat.service.HourlyStatService;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatHourlyId;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.service.StatSeriesService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540)
// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 시각은 스케줄러가 건드리지 않을 먼 미래(2099년)로 둔다
/** 통계 그래프 조회(FNC-C-012) 1시간 단위 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class StatSeriesHourlyIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final LocalDateTime H0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final LocalDateTime H1 = H0.plusHours(1);
    private static final LocalDateTime H2 = H0.plusHours(2);
    private static final LocalDateTime H3 = H0.plusHours(3);
    private static final LocalDateTime END = H0.plusHours(4);
    private static final int[] NONE = new int[4];

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private StatSeriesService statSeriesService;

    @Autowired
    private HourlyStatService hourlyStatService;

    @Autowired
    private ZoneStatHourlyRepository zoneStatHourlyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private long sajik;
    private long samcheong;
    private long deletedZone;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);

        sajik = insertZone("11110530", "사직동 1구역", "100.00");
        samcheong = insertZone("11110540", "삼청동 1구역", "300.00");
        deletedZone = insertZone("11110540", "삼청동 삭제된 구역", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-02 00:00:00' WHERE id = ?", deletedZone);

        // 사직 1구역의 동쪽 = 삼청 1구역(삼청의 서쪽 = 사직). 둘 다 종로구 안
        jdbcTemplate.update("INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, 'E', ?)", sajik, samcheong);
        jdbcTemplate.update("INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, 'W', ?)", samcheong, sajik);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·이웃·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 1h는 시간별 통계(#146이 sample_sec 가중 평균으로 묶은 값)를 그대로 준다")
    void zoneHour_sameAsHourlyAggregation() {
        // 10:00 10명(밀집 0.1, 60초), 10:01 40명(밀집 0.4, 30초), 10:02 캠 끊김(0초)
        insertMinute(sajik, H0, "10.00", "0.1000", 12, 8, new int[] {3, 0, 0, 0}, 120, 2, 60);
        insertMinute(sajik, H0.plusMinutes(1), "40.00", "0.4000", 50, 30, new int[] {5, 0, 0, 0}, 60, 1, 30);
        insertMinute(sajik, H0.plusMinutes(2), "0.00", "0.0000", 0, 0, NONE, 0, 0, 0);
        hourlyStatService.aggregateHour(H0);

        StatSeriesPoint point = series(StatTargetType.ZONE, sajik, H0, H1).get(0);

        // 손 계산: 인원 (10×60 + 40×30) ÷ 90 = 20, 밀집도 (0.1×60 + 0.4×30) ÷ 90 = 0.2,
        // 최대 50, 최소는 받은 초가 있는 분 중 8, 유입 N 3+5, 체류 (120+60) ÷ (2+1) = 60, 받은 초 60+30
        assertThat(point.at()).isEqualTo(kst(H0));
        assertThat(point.countAvg()).isEqualByComparingTo("20.00");
        assertThat(point.density()).isEqualByComparingTo("0.2000");
        assertThat(point.countMax()).isEqualTo(50);
        assertThat(point.countMin()).isEqualTo(8);
        assertThat(point.inflow()).containsEntry("N", 8);
        assertThat(point.dwellAvgSec()).isEqualByComparingTo("60.0");
        assertThat(point.sampleSec()).isEqualTo(90);
        assertThat(point.isDemo()).isFalse();

        // #146이 저장한 시간별 행과도 같다
        ZoneStatHourly hourly = zoneStatHourlyRepository.findById(new ZoneStatHourlyId(sajik, H0)).orElseThrow();
        assertThat(point.countAvg()).isEqualByComparingTo(hourly.getCountAvg());
        assertThat(point.density()).isEqualByComparingTo(hourly.getDensity());
        assertThat(point.countMax()).isEqualTo(hourly.getCountMax());
        assertThat(point.countMin()).isEqualTo(hourly.getCountMin());
        assertThat(point.sampleSec()).isEqualTo(hourly.getSampleSec());
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 1h 지역은 같은 시간 구역 값을 합하고 내부 이동을 빼며, 시연용 행이 섞이면 isDemo")
    void regionHour_sumsZonesExcludesInternalMovesAndMarksDemo() {
        insertHourlyRows();

        // from이 정시가 아니면 그 시간부터
        List<StatSeriesPoint> items = series(StatTargetType.REGION, regionId("11110"), H0.plusMinutes(30), END);

        assertThat(items).extracting(StatSeriesPoint::at).containsExactly(kst(H0), kst(H1), kst(H2), kst(H3));

        StatSeriesPoint h0 = items.get(0);
        // 100 + 60, 최대 150 + 90, 최소 50 + 30, 밀집도 160 ÷ (100 + 300)
        assertThat(h0.countAvg()).isEqualByComparingTo("160.00");
        assertThat(h0.countMax()).isEqualTo(240);
        assertThat(h0.countMin()).isEqualTo(80);
        assertThat(h0.density()).isEqualByComparingTo("0.4000");
        assertThat(h0.level()).isEqualTo(1);
        // 유입: 사직 N2·E6(뺌), 삼청 S5 / 유출: 사직 E7(뺌)·S1, 삼청 N2·W4(뺌)
        assertThat(h0.inflow()).containsExactly(
                Map.entry("N", 2), Map.entry("E", 0), Map.entry("S", 5), Map.entry("W", 0));
        assertThat(h0.outflow()).containsExactly(
                Map.entry("N", 2), Map.entry("E", 0), Map.entry("S", 1), Map.entry("W", 0));
        // (3000 + 1000) ÷ (10 + 5) = 266.66…
        assertThat(h0.dwellAvgSec()).isEqualByComparingTo("266.7");
        assertThat(h0.sampleSec()).isEqualTo(3600);
        // 삼청 행이 시연용
        assertThat(h0.isDemo()).isTrue();

        // 11시: 사직은 받은 초 0이라 빠지고 삭제된 구역 30명만, 면적 100
        StatSeriesPoint h1 = items.get(1);
        assertThat(h1.countAvg()).isEqualByComparingTo("30.00");
        assertThat(h1.density()).isEqualByComparingTo("0.3000");
        assertThat(h1.isDemo()).isFalse();

        assertThat(items.get(2)).isEqualTo(StatSeriesPoint.empty(kst(H2)));

        // 13시: 삼청 150명 ÷ 300
        assertThat(items.get(3).density()).isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("FNC-C-012 1h 구역은 저장된 밀집도 그대로, 받은 초 0인 시간은 빈 점")
    void zoneHour_storedValuesAndEmptyHour() {
        insertHourlyRows();

        List<StatSeriesPoint> items = series(StatTargetType.ZONE, sajik, H0, H2);

        StatSeriesPoint h0 = items.get(0);
        assertThat(h0.density()).isEqualByComparingTo("1.0000");
        assertThat(h0.level()).isEqualTo(2);
        // 구역 하나는 이웃이 있어도 그대로 센다
        assertThat(h0.inflow()).containsEntry("E", 6);
        assertThat(h0.dwellAvgSec()).isEqualByComparingTo("300.0");
        assertThat(h0.isDemo()).isFalse();
        assertThat(items.get(1)).isEqualTo(StatSeriesPoint.empty(kst(H1)));
    }

    @Test
    @DisplayName("FNC-C-012 API 1h: 시각은 +09:00, isDemo를 내보내고, 31일을 넘으면 400 RANGE_TOO_LONG")
    void api_hour() throws Exception {
        insertHourlyRows();

        mockMvc.perform(get("/api/v1/stats/series").with(AdminTestLogin.admin())
                        .param("targetType", "REGION")
                        .param("targetId", String.valueOf(regionId("11110")))
                        .param("interval", "1h")
                        .param("from", "2099-01-01T10:00:00+09:00")
                        .param("to", "2099-01-01T12:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].at").value("2099-01-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].isDemo").value(true))
                .andExpect(jsonPath("$.items[1].at").value("2099-01-01T11:00:00+09:00"));

        mockMvc.perform(get("/api/v1/stats/series").with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("interval", "1h")
                        .param("from", "2099-01-01")
                        .param("to", "2099-02-01T01:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("1시간 단위는 최대 31일까지 조회할 수 있습니다."));
    }

    // 시간별 행을 직접 넣는다(시연용 행은 생성 스크립트가 시간별 표에 넣으므로 1분 통계로는 만들 수 없다)
    private void insertHourlyRows() {
        // 10시: 사직 100명(밀집 1.0, 유입 N2·E6, 유출 E7·S1, 체류 3000초/10명), 삼청 60명(유입 S5, 유출 N2·W4, 체류 1000초/5명, 시연용)
        insertHourly(sajik, H0, "100.00", "1.0000", 150, 50,
                new int[] {2, 6, 0, 0}, new int[] {0, 7, 1, 0}, 3000, 10, 3600, false);
        insertHourly(samcheong, H0, "60.00", "0.2000", 90, 30,
                new int[] {0, 0, 5, 0}, new int[] {2, 0, 0, 4}, 1000, 5, 1800, true);
        // 11시: 사직 받은 초 0, 삭제된 구역 30명
        insertHourly(sajik, H1, "0.00", "0.0000", 0, 0, NONE, NONE, 0, 0, 0, false);
        insertHourly(deletedZone, H1, "30.00", "0.3000", 40, 20, NONE, NONE, 0, 0, 3600, false);
        // 12시 통계 없음, 13시 삼청 150명
        insertHourly(samcheong, H3, "150.00", "0.5000", 160, 140, NONE, NONE, 0, 0, 3600, false);
        // 조회 기간 밖
        insertHourly(sajik, H0.minusHours(1), "999.00", "9.9900", 999, 999, NONE, NONE, 0, 0, 3600, false);
        insertHourly(sajik, END, "999.00", "9.9900", 999, 999, NONE, NONE, 0, 0, 3600, false);
    }

    private List<StatSeriesPoint> series(StatTargetType targetType, long targetId, LocalDateTime from, LocalDateTime to) {
        return statSeriesService
                .findSeries(new StatSeriesRequest(targetType, targetId, StatInterval.HOUR, from, to))
                .items();
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time.atOffset(ZoneOffset.ofHours(9));
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private long regionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
    }

    // 구역 등록 API로 만들면 면적이 다각형에서 계산되므로, 계산하기 쉬운 면적을 두려고 직접 넣는다
    private long insertZone(String dongCode, String name, String areaM2) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, ?, '{}', 37.600000, 127.002000)",
                regionId(dongCode),
                name,
                new BigDecimal(areaM2));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    // in은 N·E·S·W 순서. 유출은 0
    private void insertMinute(
            long zoneId, LocalDateTime statAt, String countAvg, String density, int countMax, int countMin,
            int[] in, int dwellSumSec, int dwellCount, int sampleSec) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, dwell_sum_sec, dwell_count, sample_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                zoneId, Timestamp.valueOf(statAt), new BigDecimal(countAvg), countMax, countMin,
                new BigDecimal(density), in[0], in[1], in[2], in[3], dwellSumSec, dwellCount, sampleSec);
    }

    // in·out은 N·E·S·W 순서
    private void insertHourly(
            long zoneId, LocalDateTime statAt, String countAvg, String density, int countMax, int countMin,
            int[] in, int[] out, int dwellSumSec, int dwellCount, int sampleSec, boolean demo) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w,"
                        + " dwell_sum_sec, dwell_count, sample_sec, is_demo)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                zoneId, Timestamp.valueOf(statAt), new BigDecimal(countAvg), countMax, countMin,
                new BigDecimal(density), in[0], in[1], in[2], in[3], out[0], out[1], out[2], out[3],
                dwellSumSec, dwellCount, sampleSec, demo);
    }
}
