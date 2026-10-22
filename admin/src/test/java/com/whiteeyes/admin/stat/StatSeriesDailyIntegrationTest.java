package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
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
import com.whiteeyes.admin.stat.service.DailyStatService;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatDailyId;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.service.StatSeriesService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540)
// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 날짜는 스케줄러가 건드리지 않을 먼 미래(2099년)로 둔다
/** 통계 그래프 조회(FNC-C-012) 1일 단위 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class StatSeriesDailyIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final LocalDate D0 = LocalDate.of(2099, 1, 1);
    private static final LocalDate D1 = D0.plusDays(1);
    private static final LocalDate D2 = D0.plusDays(2);
    private static final LocalDate D3 = D0.plusDays(3);
    private static final int[] NONE = new int[4];

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private StatSeriesService statSeriesService;

    @Autowired
    private DailyStatService dailyStatService;

    @Autowired
    private ZoneStatDailyRepository zoneStatDailyRepository;

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
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-10 00:00:00' WHERE id = ?", deletedZone);

        // 사직 1구역의 동쪽 = 삼청 1구역(삼청의 서쪽 = 사직). 둘 다 종로구 안
        insertNeighbor(sajik, "E", samcheong);
        insertNeighbor(samcheong, "W", sajik);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·이웃·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 1d는 일별 통계(#146이 sample_sec 가중 평균으로 묶은 값)를 그대로 준다")
    void zoneDay_sameAsDailyAggregation() {
        // 9시 10명(밀집 0.1, 3600초), 10시 30명(밀집 0.3, 1800초, 시연용)
        insertHourly(sajik, D0.atTime(9, 0), "10.00", "0.1000", 20, 5,
                new int[] {10, 0, 0, 0}, NONE, 1000, 10, 3600, false);
        insertHourly(sajik, D0.atTime(10, 0), "30.00", "0.3000", 60, 2,
                new int[] {20, 0, 0, 0}, NONE, 500, 5, 1800, true);
        dailyStatService.aggregateDay(D0);

        StatSeriesPoint point = series(StatTargetType.ZONE, sajik, D0.atStartOfDay(), D1.atStartOfDay()).get(0);

        // 손 계산: 인원 (10×3600 + 30×1800) ÷ 5400 = 16.666…, 밀집도 (0.1×3600 + 0.3×1800) ÷ 5400 = 0.1666…,
        // 최대 60, 최소 2, 유입 N 10+20, 체류 (1000+500) ÷ (10+5) = 100, 받은 초 3600+1800, 시연용 시간이 섞여 시연용
        assertThat(point.at()).isEqualTo(kst(D0));
        assertThat(point.countAvg()).isEqualByComparingTo("16.67");
        assertThat(point.density()).isEqualByComparingTo("0.1667");
        assertThat(point.countMax()).isEqualTo(60);
        assertThat(point.countMin()).isEqualTo(2);
        assertThat(point.inflow()).containsEntry("N", 30);
        assertThat(point.dwellAvgSec()).isEqualByComparingTo("100.0");
        assertThat(point.sampleSec()).isEqualTo(5400);
        assertThat(point.isDemo()).isTrue();

        // #146이 저장한 일별 행과도 같다
        ZoneStatDaily daily = zoneStatDailyRepository.findById(new ZoneStatDailyId(sajik, D0)).orElseThrow();
        assertThat(point.countAvg()).isEqualByComparingTo(daily.getCountAvg());
        assertThat(point.density()).isEqualByComparingTo(daily.getDensity());
        assertThat(point.countMax()).isEqualTo(daily.getCountMax());
        assertThat(point.countMin()).isEqualTo(daily.getCountMin());
        assertThat(point.sampleSec()).isEqualTo(daily.getSampleSec());
        assertThat(point.isDemo()).isEqualTo(daily.isDemo());
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 1d 지역은 같은 날 구역 값을 합하고 내부 이동을 빼며, 시연용 행이 섞이면 isDemo")
    void regionDay_sumsZonesExcludesInternalMovesAndMarksDemo() {
        insertDailyRows();

        // from이 하루 중간이면 그날부터, to가 0시가 아니면 그날까지
        List<StatSeriesPoint> items = series(
                StatTargetType.REGION, regionId("11110"), D0.atTime(12, 0), D3.atTime(6, 0));

        assertThat(items).extracting(StatSeriesPoint::at).containsExactly(kst(D0), kst(D1), kst(D2), kst(D3));

        StatSeriesPoint d0 = items.get(0);
        // 100 + 60, 최대 150 + 90, 최소 50 + 30, 밀집도 160 ÷ (100 + 300)
        assertThat(d0.countAvg()).isEqualByComparingTo("160.00");
        assertThat(d0.countMax()).isEqualTo(240);
        assertThat(d0.countMin()).isEqualTo(80);
        assertThat(d0.density()).isEqualByComparingTo("0.4000");
        // 유입: 사직 N2·E6(뺌), 삼청 S5 / 유출: 사직 E7(뺌)·S1, 삼청 N2·W4(뺌)
        assertThat(d0.inflow()).containsExactly(
                Map.entry("N", 2), Map.entry("E", 0), Map.entry("S", 5), Map.entry("W", 0));
        assertThat(d0.outflow()).containsExactly(
                Map.entry("N", 2), Map.entry("E", 0), Map.entry("S", 1), Map.entry("W", 0));
        assertThat(d0.sampleSec()).isEqualTo(86400);
        // 삼청 행이 시연용
        assertThat(d0.isDemo()).isTrue();

        // 1/2: 사직은 받은 초 0이라 빠지고 삭제된 구역 30명만, 면적 100
        StatSeriesPoint d1 = items.get(1);
        assertThat(d1.countAvg()).isEqualByComparingTo("30.00");
        assertThat(d1.density()).isEqualByComparingTo("0.3000");
        assertThat(d1.isDemo()).isFalse();

        assertThat(items.get(2)).isEqualTo(StatSeriesPoint.empty(kst(D2)));

        // 1/4: 삼청 150명 ÷ 300
        assertThat(items.get(3).density()).isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("FNC-C-012 API 1d: 날짜의 0시(+09:00)로 내보내고, 1년을 넘으면 400 RANGE_TOO_LONG")
    void api_day() throws Exception {
        insertDailyRows();

        mockMvc.perform(get("/api/v1/stats/series").with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("interval", "1d")
                        .param("from", "2099-01-01")
                        .param("to", "2099-01-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].at").value("2099-01-01T00:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].density").value(1.0))
                .andExpect(jsonPath("$.items[0].level").value(2))
                .andExpect(jsonPath("$.items[0].isDemo").value(false))
                .andExpect(jsonPath("$.items[1].at").value("2099-01-02T00:00:00+09:00"))
                .andExpect(jsonPath("$.items[1].sampleSec").value(0));

        mockMvc.perform(get("/api/v1/stats/series").with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("interval", "1d")
                        .param("from", "2099-01-01")
                        .param("to", "2100-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("1일 단위는 최대 1년까지 조회할 수 있습니다."));
    }

    // 일별 행을 직접 넣는다(시연용 행은 생성 스크립트가 넣으므로 시간별 통계를 묶어서는 섞을 수 없다)
    private void insertDailyRows() {
        // 1/1: 사직 100명(밀집 1.0, 유입 N2·E6, 유출 E7·S1), 삼청 60명(유입 S5, 유출 N2·W4, 시연용)
        insertDaily(sajik, D0, "100.00", "1.0000", 150, 50,
                new int[] {2, 6, 0, 0}, new int[] {0, 7, 1, 0}, 86400, false);
        insertDaily(samcheong, D0, "60.00", "0.2000", 90, 30,
                new int[] {0, 0, 5, 0}, new int[] {2, 0, 0, 4}, 43200, true);
        // 1/2: 사직 받은 초 0, 삭제된 구역 30명
        insertDaily(sajik, D1, "0.00", "0.0000", 0, 0, NONE, NONE, 0, false);
        insertDaily(deletedZone, D1, "30.00", "0.3000", 40, 20, NONE, NONE, 86400, false);
        // 1/3 통계 없음, 1/4 삼청 150명
        insertDaily(samcheong, D3, "150.00", "0.5000", 160, 140, NONE, NONE, 86400, false);
        // 조회 기간 밖
        insertDaily(sajik, D0.minusDays(1), "999.00", "9.9900", 999, 999, NONE, NONE, 86400, false);
        insertDaily(sajik, D3.plusDays(1), "999.00", "9.9900", 999, 999, NONE, NONE, 86400, false);
    }

    private List<StatSeriesPoint> series(
            StatTargetType targetType, long targetId, LocalDateTime from, LocalDateTime to) {
        return statSeriesService
                .findSeries(new StatSeriesRequest(targetType, targetId, StatInterval.DAY, from, to))
                .items();
    }

    private static OffsetDateTime kst(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.ofHours(9));
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_daily");
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private void insertNeighbor(long zoneId, String direction, long neighborId) {
        jdbcTemplate.update(
                "INSERT INTO zone_neighbor (zone_id, direction, neighbor_id) VALUES (?, ?, ?)",
                zoneId,
                direction,
                neighborId);
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

    // in·out은 N·E·S·W 순서. 체류는 0
    private void insertDaily(
            long zoneId, LocalDate statDate, String countAvg, String density, int countMax, int countMin,
            int[] in, int[] out, int sampleSec, boolean demo) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_daily (zone_id, stat_date, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, sample_sec, is_demo)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                zoneId, Date.valueOf(statDate), new BigDecimal(countAvg), countMax, countMin,
                new BigDecimal(density), in[0], in[1], in[2], in[3], out[0], out[1], out[2], out[3],
                sampleSec, demo);
    }
}
