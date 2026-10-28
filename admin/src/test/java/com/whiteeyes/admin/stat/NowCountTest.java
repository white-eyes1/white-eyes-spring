package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.stat.service.DirectionCounts;
import com.whiteeyes.admin.stat.service.MinuteAccumulatorStore;
import com.whiteeyes.admin.stat.service.NowCountService;
import com.whiteeyes.admin.stat.service.SecondResult;
import com.whiteeyes.common.stat.dto.DashboardSummaryResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.MapStatusItem;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.service.DashboardSummaryService;
import com.whiteeyes.common.stat.service.MapStatusService;
import com.whiteeyes.common.stat.service.NowCountReader;
import com.whiteeyes.common.stat.service.StatStreamBroadcaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
/**
 * 현재 인원 저장·읽기(FNC-A-054, FNC-C-016). 5초 값은 zone_now_count에만 있고 1분 통계·이벤트·집계·엑셀과 섞이지 않는다.
 */
@SpringBootTest
class NowCountTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    // 구역·누적기가 다른 테스트와 겹치지 않도록 먼 미래의 한 분을 쓴다. 시각은 모두 직접 넘기므로 시계와 무관하다
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 12, 0, 10);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private NowCountReader nowCountReader;

    @Autowired
    private DashboardSummaryService dashboardSummaryService;

    @Autowired
    private MapStatusService mapStatusService;

    @Autowired
    private StatStreamBroadcaster broadcaster;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long samcheongZone;
    private long sajikZone;
    // 앱이 돌리는 예약 작업(실제 시각 기준)과 겹치지 않도록 이 시험만의 누적기와 저장 서비스를 쓴다
    private MinuteAccumulatorStore accumulatorStore;
    private NowCountService nowCountService;

    @BeforeEach
    void setUp() {
        accumulatorStore = new MinuteAccumulatorStore();
        nowCountService = new NowCountService(accumulatorStore, jdbcTemplate, 15);
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZone = insertZone("11110540", "삼청동 1구역");
        sajikZone = insertZone("11110530", "사직동 1구역");
    }

    @AfterEach
    void cleanUp() {
        clearAll();
    }

    @Test
    @DisplayName("FNC-A-054 구역마다 가장 최근 초의 인원을 zone_now_count에 한 행으로 저장하고 다시 저장하면 덮어쓴다")
    void saveLatest_upsertsLatestSecondPerZone() {
        addResult(samcheongZone, NOW.minusSeconds(8), 5);
        addResult(samcheongZone, NOW.minusSeconds(6), 7);
        addResult(sajikZone, NOW.minusSeconds(7), 40);

        assertThat(nowCountService.saveLatest(NOW)).isEqualTo(2);

        assertThat(personCount(samcheongZone)).isEqualTo(7);
        assertThat(updatedAt(samcheongZone)).isEqualTo(NOW.minusSeconds(6));
        assertThat(personCount(sajikZone)).isEqualTo(40);

        // 5초 뒤 더 새로운 초가 오면 같은 행을 덮어쓴다(구역당 1행, 이력 없음)
        addResult(samcheongZone, NOW.minusSeconds(1), 9);
        assertThat(nowCountService.saveLatest(NOW.plusSeconds(5))).isEqualTo(2);

        assertThat(rowCount()).isEqualTo(2);
        assertThat(personCount(samcheongZone)).isEqualTo(9);
        assertThat(updatedAt(samcheongZone)).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    @DisplayName("FNC-A-054 15초 넘게 오래된 값은 저장하지 않는다(경계: 15초는 저장, 16초는 건너뜀)")
    void saveLatest_skipsStaleResults() {
        addResult(samcheongZone, NOW.minusSeconds(15), 3);
        addResult(sajikZone, NOW.minusSeconds(16), 4);

        assertThat(nowCountService.saveLatest(NOW)).isEqualTo(1);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(personCount(samcheongZone)).isEqualTo(3);
    }

    @Test
    @DisplayName("FNC-A-054 받은 결과가 없으면 아무것도 저장하지 않는다")
    void saveLatest_nothingReceived() {
        assertThat(nowCountService.saveLatest(NOW)).isZero();
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-054 5초 값을 저장해도 1분 통계는 만들어지지 않고 실시간 갱신이 새 분으로 보지도 않는다")
    void saveLatest_doesNotTouchMinuteStat() {
        broadcaster.pollOnce();
        addResult(samcheongZone, NOW.minusSeconds(2), 11);

        nowCountService.saveLatest(NOW);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM zone_stat_minute", Integer.class)).isZero();
        assertThat(broadcaster.pollOnce()).isNull();
    }

    @Test
    @DisplayName("FNC-A-054 1분 통계·이벤트·집계·엑셀 코드는 zone_now_count를 읽지 않는다(읽는 곳은 NowCountReader 하나)")
    void onlyNowCountReaderReadsTheTable() throws IOException {
        List<String> readers = Stream.of(Paths.get("src/main/java"), Paths.get("../common/src/main/java"))
                .flatMap(NowCountTest::javaFiles)
                .filter(NowCountTest::mentionsNowCountTable)
                .map(file -> file.getFileName().toString())
                .sorted()
                .toList();

        assertThat(readers)
                .containsExactlyInAnyOrder(
                        // 표를 쓰는 곳(admin), 엔티티·저장소·읽기(common)
                        "NowCountService.java",
                        "NowCountReader.java",
                        "ZoneNowCount.java",
                        "ZoneNowCountRepository.java",
                        "StatStreamBroadcaster.java",
                        "DashboardSummaryService.java",
                        "MapStatusService.java",
                        "CameraListService.java");
    }

    @Test
    @DisplayName("FNC-C-016 신선한 현재 인원만 읽고 합은 신선한 구역끼리 더한다. 신선한 값이 없으면 빈 값")
    void reader_freshnessAndSum() {
        insertNowCount(samcheongZone, 12, NOW.minusSeconds(15));
        insertNowCount(sajikZone, 30, NOW.minusSeconds(16));

        assertThat(nowCountReader.findFresh(List.of(samcheongZone, sajikZone), NOW)).containsOnlyKeys(samcheongZone);
        assertThat(nowCountReader.sumFresh(List.of(samcheongZone, sajikZone), NOW).getAsInt()).isEqualTo(12);
        assertThat(nowCountReader.sumFresh(List.of(sajikZone), NOW)).isEmpty();
        assertThat(nowCountReader.sumFresh(List.of(), NOW)).isEmpty();
        assertThat(nowCountReader.findAllFresh(NOW)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-C-016 요약은 1분 통계가 없어도 currentCount를 주고, 지역은 신선한 구역의 합이다. countAvg는 그대로")
    void summary_currentCount() {
        insertNowCount(samcheongZone, 12, NOW.minusSeconds(3));
        insertNowCount(sajikZone, 30, NOW.minusSeconds(4));

        DashboardSummaryResponse zone = dashboardSummaryService.findSummary(DashboardTargetType.ZONE, samcheongZone, NOW);
        assertThat(zone.currentCount()).isEqualTo(12);
        assertThat(zone.countAvg()).isNull();
        assertThat(zone.statAt()).isNull();

        DashboardSummaryResponse jongno =
                dashboardSummaryService.findSummary(DashboardTargetType.REGION, regionId("11110"), NOW);
        assertThat(jongno.currentCount()).isEqualTo(42);

        // 15초가 지나면 null → 화면은 1분 평균으로 되돌린다
        DashboardSummaryResponse stale =
                dashboardSummaryService.findSummary(DashboardTargetType.ZONE, samcheongZone, NOW.plusSeconds(13));
        assertThat(stale.currentCount()).isNull();
    }

    @Test
    @DisplayName("FNC-C-009 지도 현황의 구역과 하위 지역 항목에 currentCount가 실린다(지역은 합)")
    void mapStatus_currentCount() {
        insertNowCount(samcheongZone, 12, NOW.minusSeconds(3));
        insertNowCount(sajikZone, 30, NOW.minusSeconds(20));

        List<MapStatusItem> zones =
                mapStatusService.findMapStatus(regionId("11110"), MapUnit.ZONE, NOW).items();
        assertThat(zones).extracting(MapStatusItem::id, MapStatusItem::currentCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(samcheongZone, 12),
                        org.assertj.core.api.Assertions.tuple(sajikZone, null));

        List<MapStatusItem> dongs =
                mapStatusService.findMapStatus(regionId("11"), MapUnit.CHILD, NOW).items();
        MapStatusItem jongno =
                dongs.stream().filter(item -> item.id() == regionId("11110")).findFirst().orElseThrow();
        assertThat(jongno.currentCount()).isEqualTo(12);
        assertThat(jongno.countAvg()).isNull();
    }

    private static Stream<Path> javaFiles(Path root) {
        try {
            return Files.walk(root).filter(path -> path.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // 표 이름이나 그 엔티티·저장소·읽기 클래스를 코드(주석 제외 줄)에서 쓰는 파일
    private static boolean mentionsNowCountTable(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
                    .anyMatch(line -> line.contains("zone_now_count")
                            || line.contains("ZoneNowCount")
                            || line.contains("NowCountReader"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void addResult(long zoneId, LocalDateTime second, int personCount) {
        accumulatorStore.add(
                zoneId,
                second,
                new SecondResult(personCount, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
    }

    private int personCount(long zoneId) {
        return jdbcTemplate.queryForObject(
                "SELECT person_count FROM zone_now_count WHERE zone_id = ?", Integer.class, zoneId);
    }

    private LocalDateTime updatedAt(long zoneId) {
        return jdbcTemplate
                .queryForObject("SELECT updated_at FROM zone_now_count WHERE zone_id = ?", Timestamp.class, zoneId)
                .toLocalDateTime();
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM zone_now_count", Integer.class);
    }

    private void insertNowCount(long zoneId, int personCount, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO zone_now_count (zone_id, person_count, updated_at) VALUES (?, ?, ?)",
                zoneId,
                personCount,
                Timestamp.valueOf(updatedAt));
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

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM zone_now_count");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }
}
