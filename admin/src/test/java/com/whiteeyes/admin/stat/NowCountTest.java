package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.stat.service.DirectionCounts;
import com.whiteeyes.admin.stat.service.MemoryNowCountSource;
import com.whiteeyes.admin.stat.service.MinuteAccumulatorStore;
import com.whiteeyes.admin.stat.service.SecondResult;
import com.whiteeyes.common.stat.dto.DashboardSummaryResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.MapStatusItem;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.service.DashboardSummaryService;
import com.whiteeyes.common.stat.service.MapStatusService;
import com.whiteeyes.common.stat.service.NowCountSource;
import com.whiteeyes.common.stat.service.NowCountSource.NowCount;
import com.whiteeyes.common.stat.service.StatStreamBroadcaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 * 현재 인원 제공(FNC-A-054, 결정 0018). 관리자 앱이 메모리 누적기의 가장 최근 초를 바로 읽고(15초 넘게 오래된 값은 쓰지 않음),
 * 5초 값은 1분 통계·이벤트·집계·엑셀과 섞이지 않는다. DB 표는 없다.
 */
@SpringBootTest
class NowCountTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    // 앱이 도는 예약 작업(실제 시각 기준)이 마감하지 않도록 먼 미래의 한 분을 쓴다. 시각은 모두 직접 넘기므로 시계와 무관하다
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 12, 0, 10);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private MinuteAccumulatorStore sharedStore;

    @Autowired
    private NowCountSource appNowCountSource;

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
    // 읽기 규칙만 보는 시험은 이 시험만의 누적기를 쓴다
    private MinuteAccumulatorStore store;
    private MemoryNowCountSource source;

    @BeforeEach
    void setUp() {
        store = new MinuteAccumulatorStore();
        source = new MemoryNowCountSource(store, 15);
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZone = insertZone("11110540", "삼청동 1구역");
        sajikZone = insertZone("11110530", "사직동 1구역");
    }

    @AfterEach
    void cleanUp() {
        // 앱의 공유 누적기에 넣은 결과를 비운다
        for (long zoneId : List.of(samcheongZone, sajikZone)) {
            sharedStore.closeMinute(zoneId, NOW.truncatedTo(ChronoUnit.MINUTES));
            sharedStore.closeMinute(zoneId, NOW.minusMinutes(1).truncatedTo(ChronoUnit.MINUTES));
        }
        clearAll();
    }

    @Test
    @DisplayName("FNC-A-054 구역마다 메모리 누적기의 가장 최근 초 인원을 읽는다")
    void source_readsLatestSecondPerZone() {
        add(store, samcheongZone, NOW.minusSeconds(8), 5);
        add(store, samcheongZone, NOW.minusSeconds(6), 7);
        add(store, sajikZone, NOW.minusSeconds(7), 40);

        List<NowCount> found = source.findAllFresh(NOW);

        assertThat(found)
                .extracting(NowCount::zoneId, NowCount::personCount, NowCount::at)
                .containsExactlyInAnyOrder(
                        tuple(samcheongZone, 7, NOW.minusSeconds(6)), tuple(sajikZone, 40, NOW.minusSeconds(7)));

        // 더 새로운 초가 오면 그것을 읽는다
        add(store, samcheongZone, NOW.minusSeconds(1), 9);
        assertThat(source.findFresh(List.of(samcheongZone), NOW).get(samcheongZone).personCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("FNC-A-054 분이 바뀌어도 가장 최근 초를 읽는다(앞 분은 아직 마감 전)")
    void source_latestAcrossMinutes() {
        add(store, samcheongZone, NOW.minusSeconds(12), 3); // 11:59:58
        add(store, samcheongZone, NOW.minusSeconds(8), 4); // 12:00:02

        assertThat(source.findFresh(List.of(samcheongZone), NOW).get(samcheongZone).personCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("FNC-A-054 15초 넘게 오래된 값은 쓰지 않는다(경계: 15초는 읽고 16초는 읽지 않는다)")
    void source_freshnessBoundary() {
        add(store, samcheongZone, NOW.minusSeconds(15), 3);
        add(store, sajikZone, NOW.minusSeconds(16), 4);

        assertThat(source.findAllFresh(NOW)).extracting(NowCount::zoneId).containsExactly(samcheongZone);
        assertThat(source.findFresh(List.of(samcheongZone, sajikZone), NOW)).containsOnlyKeys(samcheongZone);
    }

    @Test
    @DisplayName("FNC-C-016 합은 신선한 구역끼리 더하고, 신선한 값이 없거나 구역이 없으면 빈 값이다")
    void source_sum() {
        add(store, samcheongZone, NOW.minusSeconds(3), 12);
        add(store, sajikZone, NOW.minusSeconds(16), 30);

        assertThat(source.sumFresh(List.of(samcheongZone, sajikZone), NOW).getAsInt()).isEqualTo(12);
        assertThat(source.sumFresh(List.of(sajikZone), NOW)).isEmpty();
        assertThat(source.sumFresh(List.of(), NOW)).isEmpty();
        assertThat(source.findAllFresh(NOW.plusSeconds(60))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-054 받은 결과가 없으면 아무것도 읽히지 않는다")
    void source_nothingReceived() {
        assertThat(source.findAllFresh(NOW)).isEmpty();
        assertThat(source.findFresh(List.of(samcheongZone), NOW)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-054 관리자 앱에는 현재 인원을 내는 빈이 있고 DB 표는 없다")
    void adminApp_hasMemorySourceAndNoTable() {
        assertThat(appNowCountSource).isInstanceOf(MemoryNowCountSource.class);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'zone_now_count'",
                        Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("FNC-A-054 5초 값을 읽어도 1분 통계는 만들어지지 않고 실시간 갱신이 새 분으로 보지도 않는다")
    void source_doesNotTouchMinuteStat() {
        broadcaster.pollOnce();
        add(sharedStore, samcheongZone, NOW.minusSeconds(2), 11);

        assertThat(appNowCountSource.findAllFresh(NOW)).hasSize(1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM zone_stat_minute", Integer.class)).isZero();
        assertThat(broadcaster.pollOnce()).isNull();
    }

    @Test
    @DisplayName("FNC-A-054 1분 통계·이벤트·집계·엑셀 코드는 현재 인원을 읽지 않는다(읽는 곳은 요약·지도·캠 목록·통계 SSE뿐)")
    void onlyDisplayCodeReadsNowCount() throws IOException {
        List<String> readers = Stream.of(Paths.get("src/main/java"), Paths.get("../common/src/main/java"))
                .flatMap(NowCountTest::javaFiles)
                .filter(NowCountTest::mentionsNowCountSource)
                .map(file -> file.getFileName().toString())
                .sorted()
                .toList();

        assertThat(readers)
                .containsExactlyInAnyOrder(
                        "NowCountSource.java",
                        "MemoryNowCountSource.java",
                        "StatStreamBroadcaster.java",
                        "DashboardSummaryService.java",
                        "MapStatusService.java",
                        "CameraListService.java");
    }

    @Test
    @DisplayName("FNC-C-016 요약은 1분 통계가 없어도 currentCount를 주고, 지역은 신선한 구역의 합이다. countAvg는 그대로")
    void summary_currentCount() {
        add(sharedStore, samcheongZone, NOW.minusSeconds(3), 12);
        add(sharedStore, sajikZone, NOW.minusSeconds(4), 30);

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
        add(sharedStore, samcheongZone, NOW.minusSeconds(3), 12);
        add(sharedStore, sajikZone, NOW.minusSeconds(20), 30);

        List<MapStatusItem> zones =
                mapStatusService.findMapStatus(regionId("11110"), MapUnit.ZONE, NOW).items();
        assertThat(zones)
                .extracting(MapStatusItem::id, MapStatusItem::currentCount)
                .containsExactlyInAnyOrder(tuple(samcheongZone, 12), tuple(sajikZone, null));

        List<MapStatusItem> sigungus =
                mapStatusService.findMapStatus(regionId("11"), MapUnit.CHILD, NOW).items();
        MapStatusItem jongno =
                sigungus.stream().filter(item -> item.id() == regionId("11110")).findFirst().orElseThrow();
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

    // 현재 인원을 내는 인터페이스나 그 구현을 코드(주석 제외 줄)에서 쓰는 파일
    private static boolean mentionsNowCountSource(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
                    .anyMatch(line -> line.contains("NowCountSource"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void add(MinuteAccumulatorStore target, long zoneId, LocalDateTime second, int personCount) {
        target.add(zoneId, second, new SecondResult(personCount, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
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
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }
}
