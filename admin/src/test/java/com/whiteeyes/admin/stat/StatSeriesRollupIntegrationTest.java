package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
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
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.service.StatSeriesService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540)
// 날짜: 2099-01-05는 월요일, 2096년은 윤년. 스케줄러가 건드리지 않을 먼 미래로 둔다
/** 통계 그래프 조회(FNC-C-012) 1주·1월·1년 단위: 구역별로 일별 통계를 묶은 뒤 지역을 합산 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class StatSeriesRollupIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final int FULL_DAY = 86400;
    private static final int[] NONE = new int[4];

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private StatSeriesService statSeriesService;

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
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-12-31 00:00:00' WHERE id = ?", deletedZone);

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
    @DisplayName("FNC-C-012 검수 기준: 1w는 월요일 시작 주로 일별 통계를 sample_sec 가중 평균해 묶고, 빈 주는 빈 점")
    void zoneWeek_matchesHandCalculation() {
        insertWeekRows();

        // 목요일 from은 그 주 월요일(12/29)부터, to 1/20(화)은 1/19 주까지
        List<StatSeriesPoint> items = series(
                StatTargetType.ZONE, sajik, StatInterval.WEEK, day(2099, 1, 1), day(2099, 1, 20));

        assertThat(items).extracting(StatSeriesPoint::at).containsExactly(
                kst(2098, 12, 29), kst(2099, 1, 5), kst(2099, 1, 12), kst(2099, 1, 19));

        // 12/29 주: 일요일(1/4) 하루 10명. 다음 날 월요일(1/5)은 다른 주
        assertThat(items.get(0).countAvg()).isEqualByComparingTo("10.00");

        // 1/5 주: 월 20명(86400초)·화 50명(43200초, 시연용)·수 받은 초 0
        // 손 계산: 인원 (20×86400 + 50×43200) ÷ 129600 = 30, 밀집도 (0.2×86400 + 0.5×43200) ÷ 129600 = 0.3,
        // 최대 80, 최소는 받은 초가 있는 날 중 4, 유입 N 2+1·E 1+2, 유출 E 3+4·S 1, 체류 (1000+500) ÷ (10+5) = 100
        StatSeriesPoint week = items.get(1);
        assertThat(week.countAvg()).isEqualByComparingTo("30.00");
        assertThat(week.density()).isEqualByComparingTo("0.3000");
        assertThat(week.countMax()).isEqualTo(80);
        assertThat(week.countMin()).isEqualTo(4);
        assertThat(week.inflow()).containsExactly(
                Map.entry("N", 3), Map.entry("E", 3), Map.entry("S", 0), Map.entry("W", 0));
        assertThat(week.outflow()).containsExactly(
                Map.entry("N", 0), Map.entry("E", 7), Map.entry("S", 1), Map.entry("W", 0));
        assertThat(week.dwellAvgSec()).isEqualByComparingTo("100.0");
        assertThat(week.sampleSec()).isEqualTo(129600);
        assertThat(week.isDemo()).isTrue();

        // 1/12 주: 통계 없음, 1/19 주: 받은 초 0인 날만 → 둘 다 빈 점
        assertThat(items.get(2)).isEqualTo(StatSeriesPoint.empty(kst(2099, 1, 12)));
        assertThat(items.get(3)).isEqualTo(StatSeriesPoint.empty(kst(2099, 1, 19)));
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 1w 지역은 구역별로 먼저 묶은 값을 더하고(삭제 구역 포함), 지역 안 이동은 뺀다")
    void regionWeek_sumsRolledUpZonesAndExcludesInternalMoves() {
        insertWeekRows();

        StatSeriesPoint week = series(
                StatTargetType.REGION, regionId("11110"), StatInterval.WEEK, day(2099, 1, 5), day(2099, 1, 12)).get(0);

        // 구역별 주 평균: 사직 30, 삼청 (60+30) ÷ 2 = 45, 삭제된 구역 10 → 합 85, 면적 100+300+100
        assertThat(week.countAvg()).isEqualByComparingTo("85.00");
        assertThat(week.density()).isEqualByComparingTo("0.1700");
        // 구역별 최대 80·90·12, 최소 4·20·8의 합(명세의 근사치)
        assertThat(week.countMax()).isEqualTo(182);
        assertThat(week.countMin()).isEqualTo(32);
        // 유입: 사직 N3·E3(동쪽 이웃 삼청이 지역 안이라 뺌), 삼청 S5 / 유출: 사직 E7(뺌)·S1, 삼청 N2·W4(뺌)
        assertThat(week.inflow()).containsExactly(
                Map.entry("N", 3), Map.entry("E", 0), Map.entry("S", 5), Map.entry("W", 0));
        assertThat(week.outflow()).containsExactly(
                Map.entry("N", 2), Map.entry("E", 0), Map.entry("S", 1), Map.entry("W", 0));
        // 합산한 구역 중 가장 큰 받은 초(삼청 이틀)
        assertThat(week.sampleSec()).isEqualTo(2 * FULL_DAY);
        assertThat(week.isDemo()).isTrue();
    }

    @Test
    @DisplayName("FNC-C-012 1M은 달력 월: 1/31과 2/1은 다른 달, to가 달 중간이어도 그 달 전체를 묶는다")
    void zoneMonth_calendarMonth() {
        insertDaily(sajik, LocalDate.of(2099, 1, 31), "40.00", "0.4000", 50, 30, NONE, NONE, FULL_DAY, false);
        insertDaily(sajik, LocalDate.of(2099, 2, 1), "70.00", "0.7000", 90, 60, NONE, NONE, FULL_DAY, false);
        insertDaily(sajik, LocalDate.of(2099, 2, 28), "10.00", "0.1000", 20, 5, NONE, NONE, FULL_DAY / 2, false);

        List<StatSeriesPoint> items = series(
                StatTargetType.ZONE, sajik, StatInterval.MONTH, day(2099, 1, 15), day(2099, 2, 2));

        assertThat(items).extracting(StatSeriesPoint::at).containsExactly(kst(2099, 1, 1), kst(2099, 2, 1));
        assertThat(items.get(0).countAvg()).isEqualByComparingTo("40.00");
        // (70×86400 + 10×43200) ÷ 129600 = 50, 밀집도 0.7·0.1 같은 가중 → 0.5, 밀집도 0.5는 정상
        assertThat(items.get(1).countAvg()).isEqualByComparingTo("50.00");
        assertThat(items.get(1).density()).isEqualByComparingTo("0.5000");
        assertThat(items.get(1).level()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-C-012 1M 윤년 2월은 29일까지 한 달, 1y는 달력 연도(12/31까지, 다음 해 1/1은 제외)")
    void leapMonthAndCalendarYear() {
        insertDaily(sajik, LocalDate.of(2096, 2, 1), "10.00", "0.1000", 10, 10, NONE, NONE, FULL_DAY, false);
        insertDaily(sajik, LocalDate.of(2096, 2, 29), "30.00", "0.3000", 30, 30, NONE, NONE, FULL_DAY, false);
        insertDaily(sajik, LocalDate.of(2096, 3, 1), "999.00", "9.9900", 999, 999, NONE, NONE, FULL_DAY, false);
        insertDaily(sajik, LocalDate.of(2099, 1, 1), "10.00", "0.1000", 10, 10, NONE, NONE, FULL_DAY, false);
        insertDaily(sajik, LocalDate.of(2099, 12, 31), "30.00", "0.3000", 30, 30, NONE, NONE, FULL_DAY, true);
        insertDaily(sajik, LocalDate.of(2100, 1, 1), "999.00", "9.9900", 999, 999, NONE, NONE, FULL_DAY, false);

        List<StatSeriesPoint> february = series(
                StatTargetType.ZONE, sajik, StatInterval.MONTH, day(2096, 2, 1), day(2096, 3, 1));
        assertThat(february).hasSize(1);
        assertThat(february.get(0).countAvg()).isEqualByComparingTo("20.00");
        assertThat(february.get(0).sampleSec()).isEqualTo(2 * FULL_DAY);

        // from이 연 중간이면 그해 1/1부터
        List<StatSeriesPoint> year = series(
                StatTargetType.ZONE, sajik, StatInterval.YEAR, day(2099, 6, 15), day(2100, 1, 1));
        assertThat(year).extracting(StatSeriesPoint::at).containsExactly(kst(2099, 1, 1));
        assertThat(year.get(0).countAvg()).isEqualByComparingTo("20.00");
        assertThat(year.get(0).countMax()).isEqualTo(30);
        assertThat(year.get(0).isDemo()).isTrue();
    }

    // 화면(stats-graph.js)이 1주·1월·1년 탭을 누를 때 보내는 기본 기간 그대로 보낸다(최근 12주·12개월·5년, 내일 0시까지)
    @Test
    @DisplayName("FNC-C-012 화면이 1주·1월·1년 탭에서 보내는 기본 기간 요청이 200이고 점 수가 맞다")
    void api_screenDefaultPeriods() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate tomorrow = today.plusDays(1);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        record Case(String interval, LocalDate from, int points) {
        }
        List<Case> cases = List.of(
                new Case("1w", monday.minusWeeks(11), 12),
                new Case("1M", today.withDayOfMonth(1).minusMonths(11), 12),
                new Case("1y", LocalDate.of(today.getYear() - 4, 1, 1), 5));
        for (Case c : cases) {
            mockMvc.perform(get("/api/v1/stats/series").with(AdminTestLogin.admin())
                            .param("targetType", "REGION")
                            .param("targetId", String.valueOf(regionId("11110")))
                            .param("interval", c.interval())
                            .param("from", c.from() + "T00:00")
                            .param("to", tomorrow + "T00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(c.points()))
                    .andExpect(jsonPath("$.items[0].at").value(c.from() + "T00:00:00+09:00"));
        }

        mockMvc.perform(get("/api/v1/stats/series").with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(sajik))
                        .param("interval", "1w")
                        .param("from", "2099-01-05")
                        .param("to", "2102-01-06"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("1주 단위는 최대 3년까지 조회할 수 있습니다."));
    }

    // 사직: 1/4(일) 10명 / 1/5(월) 20명 / 1/6(화) 50명(반나절, 시연용) / 1/7(수) 받은 초 0 / 1/19(월) 받은 초 0
    // 삼청: 1/5(월) 60명 / 1/8(목) 30명, 삭제된 구역: 1/9(금) 10명
    private void insertWeekRows() {
        insertDaily(sajik, LocalDate.of(2099, 1, 4), "10.00", "0.1000", 15, 5, NONE, NONE, FULL_DAY, false);
        insertDailyWithDwell(sajik, LocalDate.of(2099, 1, 5), "20.00", "0.2000", 30, 10,
                new int[] {2, 1, 0, 0}, new int[] {0, 3, 0, 0}, 1000, 10, FULL_DAY, false);
        insertDailyWithDwell(sajik, LocalDate.of(2099, 1, 6), "50.00", "0.5000", 80, 4,
                new int[] {1, 2, 0, 0}, new int[] {0, 4, 1, 0}, 500, 5, FULL_DAY / 2, true);
        insertDaily(sajik, LocalDate.of(2099, 1, 7), "0.00", "0.0000", 0, 0, NONE, NONE, 0, false);
        insertDaily(sajik, LocalDate.of(2099, 1, 19), "0.00", "0.0000", 0, 0, NONE, NONE, 0, false);
        insertDaily(samcheong, LocalDate.of(2099, 1, 5), "60.00", "0.2000", 90, 30,
                new int[] {0, 0, 5, 0}, new int[] {2, 0, 0, 4}, FULL_DAY, false);
        insertDaily(samcheong, LocalDate.of(2099, 1, 8), "30.00", "0.1000", 40, 20, NONE, NONE, FULL_DAY, false);
        insertDaily(deletedZone, LocalDate.of(2099, 1, 9), "10.00", "0.1000", 12, 8, NONE, NONE, FULL_DAY, false);
    }

    private List<StatSeriesPoint> series(
            StatTargetType targetType, long targetId, StatInterval interval, LocalDateTime from, LocalDateTime to) {
        return statSeriesService.findSeries(new StatSeriesRequest(targetType, targetId, interval, from, to)).items();
    }

    private static LocalDateTime day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).atStartOfDay();
    }

    private static OffsetDateTime kst(int year, int month, int dayOfMonth) {
        return day(year, month, dayOfMonth).atOffset(ZoneOffset.ofHours(9));
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_daily");
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

    // 체류는 0
    private void insertDaily(
            long zoneId, LocalDate statDate, String countAvg, String density, int countMax, int countMin,
            int[] in, int[] out, int sampleSec, boolean demo) {
        insertDailyWithDwell(zoneId, statDate, countAvg, density, countMax, countMin, in, out, 0, 0, sampleSec, demo);
    }

    // in·out은 N·E·S·W 순서
    private void insertDailyWithDwell(
            long zoneId, LocalDate statDate, String countAvg, String density, int countMax, int countMin,
            int[] in, int[] out, int dwellSumSec, int dwellCount, int sampleSec, boolean demo) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_daily (zone_id, stat_date, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w,"
                        + " dwell_sum_sec, dwell_count, sample_sec, is_demo)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                zoneId, Date.valueOf(statDate), new BigDecimal(countAvg), countMax, countMin,
                new BigDecimal(density), in[0], in[1], in[2], in[3], out[0], out[1], out[2], out[3],
                dwellSumSec, dwellCount, sampleSec, demo);
    }
}
