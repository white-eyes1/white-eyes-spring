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
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.stat.dto.StatChildrenRequest;
import com.whiteeyes.common.stat.dto.StatChildrenResponse;
import com.whiteeyes.common.stat.dto.StatExportItem;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.service.StatChildrenService;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 기대값은 아래 시간별 통계로 손으로 계산한 값이다. 값은 서비스로, HTTP 형식·오류는 MockMvc로 확인한다
/** 여러 지역 비교(FNC-C-014) */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class StatChildrenIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String CHILDREN_URL = "/api/v1/stats/children";
    private static final LocalDateTime H0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final LocalDateTime H1 = H0.plusHours(1);
    private static final LocalDateTime END = H0.plusHours(2);
    private static final int[] NONE = new int[4];

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private StatChildrenService statChildrenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·이웃·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-014 검수 기준: 하위 단위 11개 이상일 때 인원 평균 상위 10개만 표시하고 나머지 수를 알린다")
    void moreThanTen_showsTopTenByCountAverage() {
        // 사직동에 구역 11개. i번째 구역의 인원 = 10×(i+1)명(10:00), 11:00은 받은 초 0. 가장 작은 첫 구역이 빠진다
        List<Long> zoneIds = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long zoneId = insertZone("11110530", "사직동 " + (i + 1) + "구역", "100.00");
            zoneIds.add(zoneId);
            insertHourly(zoneId, H0, String.valueOf(10 * (i + 1)), NONE, NONE, 3600);
            insertHourly(zoneId, H1, "0", NONE, NONE, 0);
        }
        // 삭제된 구역은 하위 단위로 세지 않는다(인원이 가장 많아도)
        long deleted = insertZone("11110530", "사직동 삭제된 구역", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-01 00:00:00' WHERE id = ?", deleted);
        insertHourly(deleted, H0, "999", NONE, NONE, 3600);

        StatChildrenResponse response = children(regionId("11110530"), StatExportItem.COUNT);

        assertThat(response.series()).hasSize(10);
        assertThat(response.omitted()).isEqualTo(1);
        // 목록 순서(구역 번호 순)는 그대로 두고 첫 구역만 빠진다
        assertThat(response.series()).extracting(StatChildrenResponse.Series::id)
                .containsExactlyElementsOf(zoneIds.subList(1, 11));
        StatChildrenResponse.Series second = response.series().get(0);
        assertThat(second.name()).isEqualTo("사직동 2구역");
        assertThat(second.points()).extracting(StatChildrenResponse.Point::at).containsExactly(kst(H0), kst(H1));
        assertThat(second.points().get(0).value()).isEqualByComparingTo("20");
        // 받은 초 0인 구간은 값이 없다(그래프 끊김)
        assertThat(second.points().get(1).value()).isNull();
    }

    @Test
    @DisplayName("FNC-C-014 10개 이하면 모두 보이고, 통계가 없는 하위 단위도 빈 계열로 준다")
    void tenOrLess_showsAll() {
        long first = insertZone("11110530", "사직동 1구역", "100.00");
        long second = insertZone("11110530", "사직동 2구역", "100.00");
        insertHourly(first, H0, "30", NONE, NONE, 3600);

        StatChildrenResponse response = children(regionId("11110530"), StatExportItem.COUNT);

        assertThat(response.omitted()).isZero();
        assertThat(response.series()).extracting(StatChildrenResponse.Series::id).containsExactly(first, second);
        assertThat(response.series().get(1).points()).extracting(StatChildrenResponse.Point::value)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("FNC-C-014 시·군·구의 하위 읍·면·동은 통계 그래프(FNC-C-012)처럼 소속 구역을 합산하고, 지역 안 이동은 뺀다")
    void sigungu_sumsZonesPerDong() {
        // 사직동: 1구역 50명·2구역 30명, 둘 사이 이동(1구역 동쪽 → 2구역 서쪽) 5명 + 바깥 유입 북쪽 2명
        long sajik1 = insertZone("11110530", "사직동 1구역", "100.00");
        long sajik2 = insertZone("11110530", "사직동 2구역", "100.00");
        long samcheong = insertZone("11110540", "삼청동 1구역", "200.00");
        insertNeighbor(sajik1, "E", sajik2);
        insertNeighbor(sajik2, "W", sajik1);
        insertHourly(sajik1, H0, "50", new int[] {2, 0, 0, 0}, new int[] {0, 5, 0, 0}, 3600);
        insertHourly(sajik2, H0, "30", new int[] {0, 0, 0, 5}, NONE, 3600);
        insertHourly(samcheong, H0, "40", new int[] {1, 1, 1, 1}, NONE, 3600);

        StatChildrenResponse count = children(regionId("11110"), StatExportItem.COUNT);
        StatChildrenResponse density = children(regionId("11110"), StatExportItem.DENSITY);
        StatChildrenResponse inflow = children(regionId("11110"), StatExportItem.INFLOW);

        assertThat(count.series()).extracting(StatChildrenResponse.Series::name).containsExactly("사직동", "삼청동");
        assertThat(count.series()).extracting(StatChildrenResponse.Series::id)
                .containsExactly(regionId("11110530"), regionId("11110540"));
        assertThat(count.series().get(0).points().get(0).value()).isEqualByComparingTo("80");
        assertThat(count.series().get(1).points().get(0).value()).isEqualByComparingTo("40");
        // 밀집도 = 80 ÷ 200㎡, 40 ÷ 200㎡
        assertThat(density.series().get(0).points().get(0).value()).isEqualByComparingTo("0.4000");
        assertThat(density.series().get(1).points().get(0).value()).isEqualByComparingTo("0.2000");
        // 사직동 유입 = 바깥 북쪽 2명(1구역↔2구역 이동 5명은 동 안 이동이라 뺀다), 삼청동 유입 = 네 방향 합 4명
        assertThat(inflow.series().get(0).points().get(0).value()).isEqualByComparingTo("2");
        assertThat(inflow.series().get(1).points().get(0).value()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("FNC-C-014 시·도의 하위 단위는 시·군·구(HTTP 응답 형식)")
    void sido_childrenAreSigungu() throws Exception {
        long sogong = insertZone("11140520", "소공동 1구역", "100.00");
        insertHourly(sogong, H0, "12.5", NONE, NONE, 3600);

        mockMvc.perform(request(String.valueOf(regionId("11")), "COUNT", "1h", "2099-01-01T10:00", "2099-01-01T12:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.omitted").value(0))
                .andExpect(jsonPath("$.series.length()").value(2))
                .andExpect(jsonPath("$.series[0].name").value("종로구"))
                .andExpect(jsonPath("$.series[0].points[0].value").isEmpty())
                .andExpect(jsonPath("$.series[1].id").value(regionId("11140")))
                .andExpect(jsonPath("$.series[1].name").value("중구"))
                .andExpect(jsonPath("$.series[1].points[0].at").value("2099-01-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.series[1].points[0].value").value(12.5))
                .andExpect(jsonPath("$.series[1].points.length()").value(2));
    }

    @Test
    @DisplayName("FNC-C-014 기간 초과는 400 RANGE_TOO_LONG, 항목·형식 오류는 400 INVALID_INPUT, 없는 지역은 404")
    void api_errors() throws Exception {
        String sido = String.valueOf(regionId("11"));
        mockMvc.perform(request(sido, "COUNT", "1m", "2099-01-01T00:00", "2099-01-02T00:01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        mockMvc.perform(request(sido, "PEOPLE", "1h", "2099-01-01T10:00", "2099-01-01T12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("item"));
        mockMvc.perform(request(sido, "COUNT", "1h", "2099-01-01T12:00", "2099-01-01T10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        mockMvc.perform(get(CHILDREN_URL).with(AdminTestLogin.admin()).param("regionId", sido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(request("999999", "COUNT", "1h", "2099-01-01T10:00", "2099-01-01T12:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    private StatChildrenResponse children(long regionId, StatExportItem item) {
        return statChildrenService.findChildren(new StatChildrenRequest(regionId, item, StatInterval.HOUR, H0, END));
    }

    private static MockHttpServletRequestBuilder request(
            String regionId, String item, String interval, String from, String to) {
        return get(CHILDREN_URL)
                .with(AdminTestLogin.admin())
                .param("regionId", regionId)
                .param("item", item)
                .param("interval", interval)
                .param("from", from)
                .param("to", to);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time.atOffset(ZoneOffset.ofHours(9));
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
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

    // 면적을 계산하기 쉽게 두려고 직접 넣는다
    private long insertZone(String dongCode, String name, String areaM2) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, ?, '{}', 37.600000, 127.002000)",
                regionId(dongCode),
                name,
                new BigDecimal(areaM2));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    // in·out은 N·E·S·W 순서. 최대·최소·밀집도는 이 시험에서 보지 않으므로 인원으로 채운다
    private void insertHourly(long zoneId, LocalDateTime statAt, String countAvg, int[] in, int[] out, int sampleSec) {
        BigDecimal count = new BigDecimal(countAvg);
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, dwell_sum_sec, dwell_count, sample_sec,"
                        + " is_demo) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 0)",
                zoneId,
                Timestamp.valueOf(statAt),
                count,
                count.intValue(),
                count.intValue(),
                in[0], in[1], in[2], in[3],
                out[0], out[1], out[2], out[3],
                sampleSec);
    }
}
