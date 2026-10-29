package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.BindException;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.common.region.exception.RegionNotFoundException;
import com.whiteeyes.common.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.stat.dto.StatExportPlan;
import com.whiteeyes.common.stat.dto.StatExportRequest;
import com.whiteeyes.common.stat.dto.StatExportScope;
import com.whiteeyes.common.stat.dto.StatExportSeries;
import com.whiteeyes.common.stat.dto.StatInterval;
import com.whiteeyes.common.stat.dto.StatSeriesPoint;
import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.exception.TooManyRowsException;
import com.whiteeyes.common.stat.service.StatExportReader;
import com.whiteeyes.common.stat.service.StatSeriesService;

/** 통계 엑셀 다운로드(FNC-C-023) 값 읽기: 대상 줄·이름·삭제 구역·행 수 제한·값 */
@SpringBootTest
class StatExportReaderIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final LocalDateTime H0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final int[] NONE = new int[4];

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private StatExportReader statExportReader;

    @Autowired
    private StatSeriesService statSeriesService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sajik;
    private long samcheong1;
    private long samcheong2;
    private long deletedZone;
    private long sogong;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);

        // 종로구: 사직동(11110530) 1개, 삼청동(11110540) 2개 + 삭제된 구역 1개. 중구 소공동(11140520) 1개
        samcheong1 = insertZone("11110540", "삼청동 1구역", "300.00");
        sajik = insertZone("11110530", "사직동 1구역", "100.00");
        deletedZone = insertZone("11110540", "삼청동 삭제된 구역", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = '2099-01-02 00:00:00' WHERE id = ?", deletedZone);
        samcheong2 = insertZone("11110540", "삼청동 2구역", "200.00");
        sogong = insertZone("11140520", "소공동 1구역", "100.00");
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-023 구역: 줄 하나에 시·구·동·구역 이름, 대상 이름은 구역 이름")
    void planZone() throws BindException {
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.ZONE, sajik, false));

        assertThat(plan.targetName()).isEqualTo("사직동 1구역");
        assertThat(plan.series()).containsExactly(
                new StatExportSeries(sajik, "서울특별시", "종로구", "사직동", "사직동 1구역"));
        assertThat(plan.rows()).isEqualTo(4);
    }

    @Test
    @DisplayName("FNC-C-023 삭제된 구역도 구역 대상으로 받는다: '구역' 열 이름에 '(삭제됨)', 대상 이름(파일 이름)에는 붙이지 않음")
    void planDeletedZone() throws BindException {
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.ZONE, deletedZone, false));

        assertThat(plan.targetName()).isEqualTo("삼청동 삭제된 구역");
        assertThat(plan.series()).containsExactly(
                new StatExportSeries(deletedZone, "서울특별시", "종로구", "삼청동", "삼청동 삭제된 구역 (삭제됨)"));
    }

    @Test
    @DisplayName("FNC-C-023 없는 구역은 404 ZONE_NOT_FOUND, 없는 지역은 404 REGION_NOT_FOUND")
    void planNotFound() {
        assertThatThrownBy(() -> statExportReader.plan(request(StatExportScope.ZONE, 999_999L, false)))
                .isInstanceOf(ZoneNotFoundException.class);
        assertThatThrownBy(() -> statExportReader.plan(request(StatExportScope.REGION, 999_999L, false)))
                .isInstanceOf(RegionNotFoundException.class);
    }

    @Test
    @DisplayName("FNC-C-023 지역 합산을 고른 경우(sum=true): 줄 하나에 지역 경로와 아래 단계 칸 '(지역 합산)', 원본 구역은 삭제된 구역까지 센다")
    void planRegionSum() throws BindException {
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.REGION, regionId("11110"), true));

        assertThat(plan.targetName()).isEqualTo("종로구");
        assertThat(plan.series()).containsExactly(
                new StatExportSeries(null, "서울특별시", "종로구", "(지역 합산)", "(지역 합산)"));
        assertThat(plan.sourceZoneCount()).isEqualTo(4);
        assertThat(plan.perZone()).isFalse();
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 3: 지역 합산 줄은 합산한 지역보다 아래 단계 칸에만 '(지역 합산)'(시·도는 구·동·구역, 동은 구역)")
    void planRegionSum_labelsBelowLevel() throws BindException {
        StatExportPlan sido = statExportReader.plan(request(StatExportScope.REGION, regionId("11"), true));
        StatExportPlan dong = statExportReader.plan(request(StatExportScope.REGION, regionId("11110540"), true));

        assertThat(sido.series()).containsExactly(
                new StatExportSeries(null, "서울특별시", "(지역 합산)", "(지역 합산)", "(지역 합산)"));
        assertThat(dong.series()).containsExactly(
                new StatExportSeries(null, "서울특별시", "종로구", "삼청동", "(지역 합산)"));
    }

    @Test
    @DisplayName("FNC-C-023 지역 기본: 삭제되지 않은 소속 구역마다 한 줄(칸마다 실제 이름), 삭제된 구역은 줄로 쓰지 않음")
    void planRegionPerZone() throws BindException {
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.REGION, regionId("11110"), false));

        assertThat(plan.series())
                .extracting(StatExportSeries::zoneId, StatExportSeries::emd, StatExportSeries::zoneName)
                .containsExactly(
                        tuple(sajik, "사직동", "사직동 1구역"),
                        tuple(samcheong1, "삼청동", "삼청동 1구역"),
                        tuple(samcheong2, "삼청동", "삼청동 2구역"));
        assertThat(plan.rows()).isEqualTo(3 * 4);
        assertThat(plan.perZone()).isTrue();
        assertThat(plan.series().get(0))
                .isEqualTo(new StatExportSeries(sajik, "서울특별시", "종로구", "사직동", "사직동 1구역"));
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 2: 줄 순서는 시·도 → 시·군·구 → 읍·면·동 → 구역 이름 가나다순, 이름이 같으면 구역 번호순")
    void planOrder_byNameThenId() throws BindException {
        long ga = insertZone("11110540", "가 구역", "100.00");
        long samcheong1Again = insertZone("11110540", "삼청동 1구역", "100.00");

        StatExportPlan region = statExportReader.plan(request(StatExportScope.REGION, regionId("11110"), false));
        StatExportPlan all = statExportReader.plan(request(StatExportScope.ALL, null, false));

        assertThat(region.series()).extracting(StatExportSeries::zoneId)
                .containsExactly(sajik, ga, samcheong1, samcheong1Again, samcheong2);
        // 시·군·구는 종로구, 중구 순
        assertThat(all.series()).extracting(StatExportSeries::zoneId)
                .containsExactly(sajik, ga, samcheong1, samcheong1Again, samcheong2, sogong);
    }

    @Test
    @DisplayName("FNC-C-023 업무 규칙: 전체(ALL)는 삭제되지 않은 구역 기준, 대상 이름은 '전체'")
    void planAll() throws BindException {
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.ALL, null, false));

        assertThat(plan.targetName()).isEqualTo("전체");
        assertThat(plan.series())
                .extracting(StatExportSeries::zoneId, StatExportSeries::sigungu)
                .containsExactly(
                        tuple(sajik, "종로구"), tuple(samcheong1, "종로구"), tuple(samcheong2, "종로구"),
                        tuple(sogong, "중구"));
    }

    @Test
    @DisplayName("FNC-C-023 검수 기준 100만 행 제한: 1분 7일(10,080개 시각) × 구역 100개는 400 TOO_MANY_ROWS")
    void planTooManyRows() throws BindException {
        // 지금 4개 + 96개 = 100개
        for (int i = 0; i < 96; i++) {
            insertZone("11140520", "소공동 추가 " + i, "100.00");
        }
        StatExportRequest week = StatExportRequest.parse(
                StatExportScope.ALL, null, "1m", "2099-01-01", "2099-01-08", List.of("COUNT"), false);

        assertThatThrownBy(() -> statExportReader.plan(week))
                .isInstanceOf(TooManyRowsException.class)
                .hasMessageContaining("예상 1,008,000행");
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 2: 지역 합산(sum=true) 값은 통계 그래프(FNC-C-012) 지역 값과 같고, 빈 시각은 값 없는 점")
    void readRegionSum_sameAsSeries() throws BindException {
        insertHourlyRows();
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.REGION, regionId("11110"), true));

        List<StatSeriesPoint> exported = readPoints(plan).get(null);

        List<StatSeriesPoint> series = statSeriesService.findSeries(new StatSeriesRequest(
                StatTargetType.REGION, regionId("11110"), StatInterval.HOUR, H0, H0.plusHours(4))).items();
        assertThat(exported).isEqualTo(series);
        // 10시: 사직 100 + 삼청1 60 = 160명(삭제된 구역 11시 30명도 합산), 12시는 통계 없음
        assertThat(exported).extracting(StatSeriesPoint::countAvg).containsExactly(
                new BigDecimal("160.00"), new BigDecimal("30.00"), null, new BigDecimal("150.00"));
        assertThat(exported.get(0).isDemo()).isTrue();
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 2: 구역별 줄 값은 구역마다 통계 그래프 구역 값과 같다(통계 없는 구역도 시각마다 한 줄)")
    void readPerZone_sameAsZoneSeries() throws BindException {
        insertHourlyRows();
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.REGION, regionId("11110"), false));

        Map<Long, List<StatSeriesPoint>> exported = readPoints(plan);

        assertThat(exported.keySet()).containsExactly(sajik, samcheong1, samcheong2);
        for (long zoneId : List.of(sajik, samcheong1, samcheong2)) {
            assertThat(exported.get(zoneId)).isEqualTo(statSeriesService.findSeries(new StatSeriesRequest(
                    StatTargetType.ZONE, zoneId, StatInterval.HOUR, H0, H0.plusHours(4))).items());
        }
        assertThat(exported.get(samcheong2)).hasSize(4).allSatisfy(point -> assertThat(point.countAvg()).isNull());
        // 시연용 표시는 구역 줄에도 그대로(삼청1 10시)
        assertThat(exported.get(samcheong1)).extracting(StatSeriesPoint::isDemo)
                .containsExactly(true, false, false, false);
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 2: 구역별 행은 시각 오름차순으로 묶고 같은 시각 안에서는 계획의 줄 순서")
    void readPerZone_timeMajorOrder() throws BindException {
        insertHourlyRows();
        StatExportPlan plan = statExportReader.plan(request(StatExportScope.REGION, regionId("11110"), false));

        List<String> rows = new ArrayList<>();
        statExportReader.read(plan, (line, point) -> rows.add(point.at().getHour() + "@" + line.zoneId()));

        assertThat(rows).containsExactly(
                "10@" + sajik, "10@" + samcheong1, "10@" + samcheong2,
                "11@" + sajik, "11@" + samcheong1, "11@" + samcheong2,
                "12@" + sajik, "12@" + samcheong1, "12@" + samcheong2,
                "13@" + sajik, "13@" + samcheong1, "13@" + samcheong2);
    }

    private Map<Long, List<StatSeriesPoint>> readPoints(StatExportPlan plan) {
        Map<Long, List<StatSeriesPoint>> result = new LinkedHashMap<>();
        statExportReader.read(plan, (line, point) ->
                result.computeIfAbsent(line.zoneId(), id -> new ArrayList<>()).add(point));
        return result;
    }

    private void insertHourlyRows() {
        // 10시: 사직 100명, 삼청1 60명(시연용). 11시: 삭제된 구역 30명. 12시 없음. 13시: 삼청1 150명
        insertHourly(sajik, H0, "100.00", "1.0000", 150, 50, 3600, false);
        insertHourly(samcheong1, H0, "60.00", "0.2000", 90, 30, 1800, true);
        insertHourly(deletedZone, H0.plusHours(1), "30.00", "0.3000", 40, 20, 3600, false);
        insertHourly(samcheong1, H0.plusHours(3), "150.00", "0.5000", 160, 140, 3600, false);
    }

    // 10시부터 4시간, 인원만
    private static StatExportRequest request(StatExportScope scope, Long targetId, boolean sum)
            throws BindException {
        return StatExportRequest.parse(
                scope, targetId, "1h", "2099-01-01T10:00", "2099-01-01T14:00", List.of("COUNT"), sum);
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_neighbor");
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

    private void insertHourly(
            long zoneId, LocalDateTime statAt, String countAvg, String density, int countMax, int countMin,
            int sampleSec, boolean demo) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w,"
                        + " dwell_sum_sec, dwell_count, sample_sec, is_demo)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?)",
                zoneId, Timestamp.valueOf(statAt), new BigDecimal(countAvg), countMax, countMin,
                new BigDecimal(density), sampleSec, demo);
    }
}
