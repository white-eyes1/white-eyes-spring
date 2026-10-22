package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.stat.service.StatStreamBroadcaster;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
/**
 * 실시간 통계 갱신(FNC-C-022). 검수 기준: 두 브라우저에서 같은 분 통계가 동시에 반영, DB 조회 횟수가 접속자 수와 무관.
 * 5초 확인 작업은 테스트에서 꺼 두고 {@code pollOnce()}를 직접 불러 한 번씩 돌린다.
 */
@Import(AdminTestUserConfig.class)
// 테스트용 application.yml이 운영 설정을 가려 open-in-view가 켜지면 끝나지 않는 SSE 요청이 DB 연결을 붙들어 연결 풀이 바닥난다
@SpringBootTest(
        properties = {
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "spring.jpa.open-in-view=false"
        })
@AutoConfigureMockMvc
class StatStreamTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String STREAM_URL = "/api/v1/stats/stream";
    // 컨텍스트와 방송 객체가 테스트들 사이에 공유되므로 분이 항상 앞선 시각으로 늘려 간다
    private static final AtomicLong MINUTES = new AtomicLong(0);
    private static final LocalDateTime BASE = LocalDateTime.of(2097, 1, 1, 0, 0);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private StatStreamBroadcaster broadcaster;

    @Autowired
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    @Autowired
    private RegionQueryService regionQueryService;

    @Autowired
    private LevelService levelService;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private long samcheongZone;
    private long sajikZone;
    private long sogongZone;

    @BeforeEach
    void setUp() {
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZone = insertZone("11110540", "삼청동 1구역");
        sajikZone = insertZone("11110530", "사직동 1구역");
        sogongZone = insertZone("11140520", "소공동 1구역");
        // 방송 객체가 처음 확인할 때 이미 있던 분을 '새 분'으로 보내지 않으므로 먼저 한 번 돌려 둔다
        broadcaster.pollOnce();
    }

    // 끊기지 않은 연결을 닫고, stat·zone·region을 남기지 않아야 뒤에 도는 다른 테스트가 FK로 실패하지 않는다
    @AfterEach
    void cleanUp() {
        broadcaster.stop();
        clearAll();
    }

    @Test
    @DisplayName("FNC-C-022 연결하면 text/event-stream으로 retry 5000과 연결 확인 줄이 오고 버퍼링 방지 헤더가 붙는다")
    void connect_sendsRetryAndHeaders() throws Exception {
        MvcResult result = connect(null);

        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-cache");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("retry:5000").contains(":connected");
    }

    @Test
    @DisplayName("FNC-C-022 새 1분 통계가 저장되면 두 브라우저가 같은 분 통계를 함께 받고 지역에 속한 구역만 받는다")
    void newMinute_deliveredToAllSubscribers_filteredByRegion() throws Exception {
        MvcResult samcheongOnly = connect(regionId("11110540"));
        MvcResult jongno = connect(regionId("11110"));
        MvcResult jongnoTwin = connect(regionId("11110"));
        MvcResult junggu = connect(regionId("11140"));
        MvcResult everything = connect(null);

        LocalDateTime minute = nextMinute();
        insertStat(samcheongZone, minute, "50.00", "0.5000");
        insertStat(sajikZone, minute, "80.00", "0.8000");
        insertStat(sogongZone, minute, "160.00", "1.6000");
        assertThat(broadcaster.pollOnce()).isEqualTo(minute);

        String samcheong = eventsOf(samcheongOnly);
        assertThat(samcheong).contains("event:stat").contains("\"zoneId\":" + samcheongZone);
        assertThat(samcheong).doesNotContain("\"zoneId\":" + sajikZone).doesNotContain("\"zoneId\":" + sogongZone);

        String jongnoBody = eventsOf(jongno);
        assertThat(jongnoBody).contains("\"zoneId\":" + samcheongZone).contains("\"zoneId\":" + sajikZone);
        assertThat(jongnoBody).doesNotContain("\"zoneId\":" + sogongZone);
        // 같은 지역을 보는 두 브라우저는 같은 내용을 받는다
        assertThat(eventsOf(jongnoTwin)).isEqualTo(jongnoBody);

        assertThat(eventsOf(junggu)).contains("\"zoneId\":" + sogongZone).doesNotContain("\"zoneId\":" + samcheongZone);
        String all = eventsOf(everything);
        assertThat(all).contains("\"zoneId\":" + samcheongZone, "\"zoneId\":" + sajikZone, "\"zoneId\":" + sogongZone);
    }

    @Test
    @DisplayName("FNC-C-022 이벤트 데이터는 statAt(+09:00)과 구역별 countAvg·density·level이다")
    void eventData_format() throws Exception {
        MvcResult stream = connect(regionId("11140"));
        LocalDateTime minute = nextMinute();
        insertStat(sogongZone, minute, "160.00", "1.6000");
        broadcaster.pollOnce();

        String data = dataLines(stream).get(0);
        var json = jsonMapper.readTree(data);
        assertThat(json.get("statAt").asString())
                .isEqualTo(minute.atOffset(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        assertThat(json.get("items")).hasSize(1);
        var item = json.get("items").get(0);
        assertThat(item.get("zoneId").asLong()).isEqualTo(sogongZone);
        assertThat(item.get("countAvg").decimalValue()).isEqualByComparingTo("160.00");
        assertThat(item.get("density").decimalValue()).isEqualByComparingTo("1.6000");
        // 밀집도 1.6 ≥ 1.5 → 위험(3)
        assertThat(item.get("level").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("FNC-C-022 혼잡 단계는 밀집도 경계(0.69 / 0.7 / 1.5)를 따른다")
    void eventData_levelBoundaries() throws Exception {
        MvcResult stream = connect(null);
        LocalDateTime minute = nextMinute();
        insertStat(samcheongZone, minute, "10.00", "0.6900");
        insertStat(sajikZone, minute, "10.00", "0.7000");
        insertStat(sogongZone, minute, "10.00", "1.5000");
        broadcaster.pollOnce();

        var items = jsonMapper.readTree(dataLines(stream).get(0)).get("items");
        for (var item : items) {
            long zone = item.get("zoneId").asLong();
            int expected = zone == samcheongZone ? 1 : zone == sajikZone ? 2 : 3;
            assertThat(item.get("level").asInt()).as("zone " + zone).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("FNC-C-022 새 분이 없으면 다시 확인해도 보내지 않고, 같은 분은 한 번만 보낸다")
    void noNewMinute_noEvent() throws Exception {
        MvcResult stream = connect(null);
        LocalDateTime minute = nextMinute();
        insertStat(samcheongZone, minute, "10.00", "0.1000");

        assertThat(broadcaster.pollOnce()).isEqualTo(minute);
        assertThat(broadcaster.pollOnce()).isNull();
        assertThat(broadcaster.pollOnce()).isNull();

        assertThat(dataLines(stream)).hasSize(1);
        // 더 오래된 분이 나중에 들어와도(보충 집계) 새 분이 아니다
        insertStat(sajikZone, minute.minusMinutes(5), "10.00", "0.1000");
        assertThat(broadcaster.pollOnce()).isNull();
        assertThat(dataLines(stream)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-C-022 다음 분이 저장되면 다시 한 번 보낸다")
    void nextMinute_sentAgain() throws Exception {
        MvcResult stream = connect(null);
        insertStat(samcheongZone, nextMinute(), "10.00", "0.1000");
        broadcaster.pollOnce();
        insertStat(samcheongZone, nextMinute(), "20.00", "0.2000");
        broadcaster.pollOnce();

        assertThat(dataLines(stream)).hasSize(2);
    }

    @Test
    @DisplayName("FNC-C-022 시작할 때 이미 저장돼 있던 분은 새 분으로 보내지 않는다")
    void startup_existingMinute_notSent() {
        LocalDateTime existing = nextMinute();
        insertStat(samcheongZone, existing, "10.00", "0.1000");
        StatStreamBroadcaster fresh =
                new StatStreamBroadcaster(zoneStatMinuteRepository, regionQueryService, levelService, jsonMapper, false);

        assertThat(fresh.pollOnce()).isNull();

        LocalDateTime next = nextMinute();
        insertStat(samcheongZone, next, "10.00", "0.1000");
        assertThat(fresh.pollOnce()).isEqualTo(next);
    }

    @Test
    @DisplayName("FNC-C-022 구독자가 없으면 새 분이 와도 구독자 조회는 하지 않는다")
    void noSubscribers_nothingSent() {
        insertStat(samcheongZone, nextMinute(), "10.00", "0.1000");

        assertThat(broadcaster.pollOnce()).isNotNull();
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    @DisplayName("FNC-C-022 DB 조회 횟수는 접속자 수와 무관하다(접속자 1명과 5명이 같다)")
    void queryCount_independentOfSubscribers() throws Exception {
        long regionId = regionId("11110");
        connect(regionId);
        long withOne = countStatementsOfNextMinute();

        connect(regionId);
        connect(regionId);
        connect(regionId);
        connect(regionId);
        long withFive = countStatementsOfNextMinute();

        assertThat(withOne).isPositive();
        assertThat(withFive).isEqualTo(withOne);
        // 새 분이 없을 때는 최신 시각 확인 한 번뿐이다
        Statistics statistics = statistics();
        statistics.clear();
        broadcaster.pollOnce();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-C-022 30초마다 주석 줄(keepalive)을 보내 연결을 유지한다")
    void heartbeat_sendsComment() throws Exception {
        MvcResult stream = connect(null);

        broadcaster.sendHeartbeatNow();

        assertThat(stream.getResponse().getContentAsString()).contains(":keepalive");
    }

    @Test
    @DisplayName("FNC-C-022 없는 지역은 404 REGION_NOT_FOUND")
    void unknownRegion_notFound() throws Exception {
        mockMvc.perform(get(STREAM_URL).param("regionId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-C-022 로그인하지 않으면 401")
    void notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(get(STREAM_URL).with(anonymous())).andExpect(status().isUnauthorized());
    }

    private long countStatementsOfNextMinute() {
        insertStat(samcheongZone, nextMinute(), "10.00", "0.1000");
        Statistics statistics = statistics();
        statistics.clear();
        broadcaster.pollOnce();
        return statistics.getPrepareStatementCount();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private MvcResult connect(Long regionId) throws Exception {
        var request = get(STREAM_URL);
        if (regionId != null) {
            request = request.param("regionId", String.valueOf(regionId));
        }
        return mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
    }

    private String eventsOf(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    // 응답 본문에서 event: stat 의 data 줄만 순서대로
    private java.util.List<String> dataLines(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString().lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()))
                .toList();
    }

    private LocalDateTime nextMinute() {
        return BASE.plusMinutes(MINUTES.incrementAndGet());
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

    private void insertStat(long zoneId, LocalDateTime statAt, String countAvg, String density) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, sample_sec)"
                        + " VALUES (?, ?, ?, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, 60)",
                zoneId,
                Timestamp.valueOf(statAt),
                new BigDecimal(countAvg),
                new BigDecimal(density));
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }
}
