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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.service.StatSeriesService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 기대값은 아래 setUp의 1분 통계로 손으로 계산한 값이다. 값은 서비스로, HTTP 형식·오류는 MockMvc로 확인한다
/** 통계 그래프 조회(FNC-C-012) 1분 단위 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class StatSeriesIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SERIES_URL = "/api/v1/stats/series";
    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final LocalDateTime T1 = T0.plusMinutes(1);
    private static final LocalDateTime T2 = T0.plusMinutes(2);
    private static final LocalDateTime T3 = T0.plusMinutes(3);
    private static final LocalDateTime END = T0.plusMinutes(4);
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
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-01 12:00:00' WHERE id = ?", deletedZone);

        // 사직 1구역의 동쪽 = 삼청 1구역(삼청의 서쪽 = 사직). 둘 다 종로구 안, 서로 다른 동
        insertNeighbor(sajik, "E", samcheong);
        insertNeighbor(samcheong, "W", sajik);

        // 10:00 사직 50명(유입 N1·E2, 유출 E3·S3, 체류 600초/4명), 삼청 30명(유입 S4, 유출 N1·W2, 체류 300초/2명, 30초만 받음)
        insertStat(sajik, T0, "50.00", "0.5000", 60, 40, new int[] {1, 2, 0, 0}, new int[] {0, 3, 3, 0}, 600, 4, 60);
        insertStat(samcheong, T0, "30.00", "0.1000", 40, 20,
                new int[] {0, 0, 4, 0}, new int[] {1, 0, 0, 2}, 300, 2, 30);
        // 10:01 사직은 캠이 끊겨 받은 초 0, 삼청 45명
        insertStat(sajik, T1, "0.00", "0.0000", 0, 0, NONE, NONE, 0, 0, 0);
        insertStat(samcheong, T1, "45.00", "0.1500", 50, 40, NONE, NONE, 0, 0, 60);
        // 10:02 통계 없음
        // 10:03 사직 80명(유출 E5), 삭제된 구역 20명
        insertStat(sajik, T3, "80.00", "0.8000", 90, 70, NONE, new int[] {0, 5, 0, 0}, 0, 0, 60);
        insertStat(deletedZone, T3, "20.00", "0.2000", 25, 15, NONE, NONE, 0, 0, 60);
        // 조회 기간(10:00 이상 10:04 미만) 밖
        insertStat(sajik, T0.minusMinutes(1), "999.00", "9.9900", 999, 999, NONE, NONE, 0, 0, 60);
        insertStat(sajik, END, "999.00", "9.9900", 999, 999, NONE, NONE, 0, 0, 60);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·이웃·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 구역의 1분 통계를 저장된 값 그대로 시각 순으로 주고, 받은 초 0·통계 없는 분은 빈 점")
    void zoneSeries_matchesHandCalculation() {
        List<StatSeriesPoint> items = series(StatTargetType.ZONE, sajik, T0, END);

        assertThat(items).extracting(StatSeriesPoint::at)
                .containsExactly(kst(T0), kst(T1), kst(T2), kst(T3));

        StatSeriesPoint t0 = items.get(0);
        assertThat(t0.countAvg()).isEqualByComparingTo("50.00");
        assertThat(t0.countMax()).isEqualTo(60);
        assertThat(t0.countMin()).isEqualTo(40);
        assertThat(t0.density()).isEqualByComparingTo("0.5000");
        assertThat(t0.level()).isEqualTo(1);
        // 구역 하나는 이웃이 있어도 그대로 센다
        assertThat(t0.inflow()).containsExactly(
                Map.entry("N", 1), Map.entry("E", 2), Map.entry("S", 0), Map.entry("W", 0));
        assertThat(t0.outflow()).containsExactly(
                Map.entry("N", 0), Map.entry("E", 3), Map.entry("S", 3), Map.entry("W", 0));
        // 600 ÷ 4
        assertThat(t0.dwellAvgSec()).isEqualByComparingTo("150.0");
        assertThat(t0.sampleSec()).isEqualTo(60);
        assertThat(t0.isDemo()).isFalse();

        // 10:01 받은 초 0, 10:02 행 없음 → 둘 다 빈 점
        assertThat(items.get(1)).isEqualTo(StatSeriesPoint.empty(kst(T1)));
        assertThat(items.get(2)).isEqualTo(StatSeriesPoint.empty(kst(T2)));

        // 밀집도 0.8은 주의(0.7 이상)
        StatSeriesPoint t3 = items.get(3);
        assertThat(t3.countAvg()).isEqualByComparingTo("80.00");
        assertThat(t3.level()).isEqualTo(2);
        assertThat(t3.outflow()).containsEntry("E", 5);
        assertThat(t3.dwellAvgSec()).isNull();
    }

    @Test
    @DisplayName("FNC-C-012 검수 기준: 지역은 같은 시각 구역 값을 합하고(삭제된 구역 포함), 지역 안 구역끼리의 이동은 뺀다")
    void regionSeries_sumsZonesAndExcludesInternalMoves() {
        List<StatSeriesPoint> items = series(StatTargetType.REGION, regionId("11110"), T0, END);

        assertThat(items).hasSize(4);

        StatSeriesPoint t0 = items.get(0);
        // 50 + 30
        assertThat(t0.countAvg()).isEqualByComparingTo("80.00");
        assertThat(t0.countMax()).isEqualTo(100);
        assertThat(t0.countMin()).isEqualTo(60);
        // 80 ÷ (100 + 300)
        assertThat(t0.density()).isEqualByComparingTo("0.2000");
        assertThat(t0.level()).isEqualTo(1);
        // 유입: 사직 N1·E2(동쪽 이웃 삼청이 종로구 안이라 뺌), 삼청 S4 → N1·S4
        assertThat(t0.inflow()).containsExactly(
                Map.entry("N", 1), Map.entry("E", 0), Map.entry("S", 4), Map.entry("W", 0));
        // 유출: 사직 E3(뺌)·S3, 삼청 N1·W2(서쪽 이웃 사직이 종로구 안이라 뺌) → N1·S3
        assertThat(t0.outflow()).containsExactly(
                Map.entry("N", 1), Map.entry("E", 0), Map.entry("S", 3), Map.entry("W", 0));
        // (600 + 300) ÷ (4 + 2)
        assertThat(t0.dwellAvgSec()).isEqualByComparingTo("150.0");
        // 합산한 구역 중 가장 큰 받은 초
        assertThat(t0.sampleSec()).isEqualTo(60);

        // 10:01 사직은 받은 초 0이라 인원·면적에서 빠지고 삼청만: 45 ÷ 300
        StatSeriesPoint t1 = items.get(1);
        assertThat(t1.countAvg()).isEqualByComparingTo("45.00");
        assertThat(t1.density()).isEqualByComparingTo("0.1500");
        assertThat(t1.countMax()).isEqualTo(50);
        assertThat(t1.dwellAvgSec()).isNull();

        assertThat(items.get(2)).isEqualTo(StatSeriesPoint.empty(kst(T2)));

        // 10:03 사직 80 + 삭제된 구역 20, 면적 100 + 100. 사직의 동쪽 유출 5는 이웃 삼청이 지역 안이라 뺀다
        StatSeriesPoint t3 = items.get(3);
        assertThat(t3.countAvg()).isEqualByComparingTo("100.00");
        assertThat(t3.countMax()).isEqualTo(115);
        assertThat(t3.countMin()).isEqualTo(85);
        assertThat(t3.density()).isEqualByComparingTo("0.5000");
        assertThat(t3.level()).isEqualTo(1);
        assertThat(t3.outflow()).containsEntry("E", 0);
    }

    @Test
    @DisplayName("FNC-C-012 이웃이 지역 밖이면 그 방향 이동은 그대로 센다(사직동만 보면 동쪽 이웃 삼청은 밖)")
    void regionSeries_neighborOutsideRegion_counted() {
        StatSeriesPoint t0 = series(StatTargetType.REGION, regionId("11110530"), T0, T1).get(0);

        assertThat(t0.inflow()).containsEntry("E", 2);
        assertThat(t0.outflow()).containsEntry("E", 3);
        assertThat(t0.density()).isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("FNC-C-012 구역이 없는 지역은 모든 분이 빈 점, from이 분 중간이면 그 분부터")
    void emptyRegionAndUnalignedFrom() {
        List<StatSeriesPoint> items = series(StatTargetType.REGION, regionId("11140"), T0.plusSeconds(30), T2);

        assertThat(items).containsExactly(StatSeriesPoint.empty(kst(T0)), StatSeriesPoint.empty(kst(T1)));
    }

    @Test
    @DisplayName("FNC-C-012 삭제된 구역을 대상으로 고르면 없는 구역으로 본다")
    void deletedZone_notFound() {
        assertThatThrownBy(() -> series(StatTargetType.ZONE, deletedZone, T0, END))
                .isInstanceOf(ZoneNotFoundException.class);
    }

    @Test
    @DisplayName("FNC-C-012 API는 명세의 필드 이름으로 내보내고, 시각은 +09:00, 빈 점은 null")
    void api_responseShape() throws Exception {
        String body = mockMvc.perform(request("REGION", String.valueOf(regionId("11110")), "1m",
                        "2099-01-01T10:00:00+09:00", "2099-01-01T10:04:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].at").value("2099-01-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].countAvg").value(80.00))
                .andExpect(jsonPath("$.items[0].inflow.N").value(1))
                .andExpect(jsonPath("$.items[0].outflow.S").value(3))
                .andExpect(jsonPath("$.items[0].dwellAvgSec").value(150.0))
                .andExpect(jsonPath("$.items[0].sampleSec").value(60))
                .andExpect(jsonPath("$.items[0].isDemo").value(false))
                .andExpect(jsonPath("$.items[2].at").value("2099-01-01T10:02:00+09:00"))
                .andExpect(jsonPath("$.items[2].sampleSec").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains(
                "\"countMax\":100", "\"countMin\":60", "\"density\":0.2000", "\"level\":1",
                "\"countAvg\":null", "\"inflow\":null", "\"outflow\":null", "\"dwellAvgSec\":null");

        // UTC로 보내도 KST로 바꿔 같은 분을 준다. 1일(1,440분)까지는 된다
        mockMvc.perform(request("ZONE", String.valueOf(sajik), "1m", "2099-01-01T01:00:00Z", "2099-01-01T01:01:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].at").value("2099-01-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].countAvg").value(50.00));
        mockMvc.perform(request("ZONE", String.valueOf(sajik), "1m", "2099-01-01", "2099-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1440));
    }

    @Test
    @DisplayName("FNC-C-012 기간 초과는 400 RANGE_TOO_LONG, 형식 오류는 400 INVALID_INPUT, 없는 대상은 404")
    void api_errors() throws Exception {
        mockMvc.perform(request("ZONE", String.valueOf(sajik), "1m", "2099-01-01T00:00", "2099-01-02T00:01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("1분 단위는 최대 1일까지 조회할 수 있습니다."));

        mockMvc.perform(request("ZONE", String.valueOf(sajik), "1m", "2099-01-01T10:00", "2099-01-01T10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        mockMvc.perform(request("ZONE", String.valueOf(sajik), "5m", "abc", "2099-01-01T10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("interval"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("from"));
        mockMvc.perform(request("DONG", "1", "1m", "2099-01-01T10:00", "2099-01-01T10:01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get(SERIES_URL).with(AdminTestLogin.admin())
                        .param("targetType", "ZONE")
                        .param("interval", "1m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(request("REGION", "999999", "1m", "2099-01-01T10:00", "2099-01-01T10:01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mockMvc.perform(request("ZONE", String.valueOf(deletedZone), "1m", "2099-01-01T10:00", "2099-01-01T10:01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    private List<StatSeriesPoint> series(
            StatTargetType targetType, long targetId, LocalDateTime from, LocalDateTime to) {
        return statSeriesService
                .findSeries(new StatSeriesRequest(targetType, targetId, StatInterval.MINUTE, from, to))
                .items();
    }

    private static MockHttpServletRequestBuilder request(
            String targetType, String targetId, String interval, String from, String to) {
        return get(SERIES_URL)
                .with(AdminTestLogin.admin())
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("interval", interval)
                .param("from", from)
                .param("to", to);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time.atOffset(ZoneOffset.ofHours(9));
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
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
    private void insertStat(
            long zoneId, LocalDateTime statAt, String countAvg, String density, int countMax, int countMin,
            int[] in, int[] out, int dwellSumSec, int dwellCount, int sampleSec) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, dwell_sum_sec, dwell_count, sample_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                zoneId,
                Timestamp.valueOf(statAt),
                new BigDecimal(countAvg),
                countMax,
                countMin,
                new BigDecimal(density),
                in[0], in[1], in[2], in[3],
                out[0], out[1], out[2], out[3],
                dwellSumSec,
                dwellCount,
                sampleSec);
    }
}
