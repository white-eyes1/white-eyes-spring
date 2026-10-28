package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.nio.file.Paths;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class CameraListTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String CAMERAS_URL = "/api/v1/manage/cameras";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";

    // 삼청동(종로구) / 소공동(중구) / 사직동(종로구) 안의 사각형
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final String SAJIK_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[126.96,37.56],[126.962,37.56],[126.962,37.562],[126.96,37.562],[126.96,37.56]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private CameraRegisterService cameraRegisterService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long samcheongZoneId;
    private long sogongZoneId;
    private long sajikZoneId;

    @BeforeEach
    void prepareZones() {
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZoneId = createZone("삼청동 1구역", SAMCHEONG_SQUARE);
        sogongZoneId = createZone("소공동 1구역", SOGONG_SQUARE);
        sajikZoneId = createZone("사직동 1구역", SAJIK_SQUARE);
    }

    // camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-022 캠이 없으면 빈 목록")
    void list_noCameras_empty() throws Exception {
        list(null).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-A-022 캠 정보와 구역 이름·지역 경로·면적을 함께 돌려준다")
    void list_returnsCameraWithZoneAndRegionInfo() throws Exception {
        long cameraId = createCamera(samcheongZoneId, "삼청동 캠");

        list(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(cameraId))
                .andExpect(jsonPath("$[0].zoneId").value(samcheongZoneId))
                .andExpect(jsonPath("$[0].zoneName").value("삼청동 1구역"))
                .andExpect(jsonPath("$[0].regionPath").value("종로구 · 삼청동"))
                .andExpect(jsonPath("$[0].areaM2").isNumber())
                .andExpect(jsonPath("$[0].name").value("삼청동 캠"))
                .andExpect(jsonPath("$[0].sourceType").value("LIVE"))
                .andExpect(jsonPath("$[0].sourceUri").value(LIVE_URI))
                .andExpect(jsonPath("$[0].status").value("STOPPED"))
                .andExpect(jsonPath("$[0].errorMessage").value(nullValue()))
                .andExpect(jsonPath("$[0].runRequested").value(false));
    }

    @Test
    @DisplayName("FNC-A-022 준비 중인 캠만 진행률을 돌려주고, 오류 내용도 보인다")
    void list_progressOnlyWhilePreparing_andErrorMessage() throws Exception {
        long preparing = createCamera(samcheongZoneId, "준비 캠");
        long running = createCamera(sogongZoneId, "동작 캠");
        long error = createCamera(sajikZoneId, "오류 캠");
        jdbcTemplate.update("UPDATE camera SET status = 'PREPARING', progress = 40 WHERE id = ?", preparing);
        jdbcTemplate.update("UPDATE camera SET status = 'RUNNING', progress = 100 WHERE id = ?", running);
        jdbcTemplate.update(
                "UPDATE camera SET status = 'ERROR', progress = 0, error_message = '파일 없음' WHERE id = ?", error);

        list(null)
                .andExpect(jsonPath("$[0].status").value("PREPARING"))
                .andExpect(jsonPath("$[0].progress").value(40))
                .andExpect(jsonPath("$[1].status").value("RUNNING"))
                .andExpect(jsonPath("$[1].progress").value(nullValue()))
                .andExpect(jsonPath("$[2].status").value("ERROR"))
                .andExpect(jsonPath("$[2].progress").value(nullValue()))
                .andExpect(jsonPath("$[2].errorMessage").value("파일 없음"));
    }

    @Test
    @DisplayName("FNC-A-022 삭제된 캠은 목록에서 빠지고 캠 번호 순으로 정렬한다")
    void list_excludesDeleted_andOrdersById() throws Exception {
        long first = createCamera(samcheongZoneId, "첫 캠");
        long deleted = createCamera(sogongZoneId, "삭제될 캠");
        long third = createCamera(sajikZoneId, "셋째 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);

        list(null)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", contains((int) first, (int) third)));
    }

    @Test
    @DisplayName("FNC-A-022 1분 통계와 오늘 이벤트가 없는 캠은 stat이 null, recent10m이 빈 목록이고 todayEventCount는 0")
    void list_noStats_statNullAndRecentEmpty() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");

        list(null)
                .andExpect(jsonPath("$[0].stat").value(nullValue()))
                .andExpect(jsonPath("$[0].todayEventCount").value(0))
                .andExpect(jsonPath("$[0].recent10m", empty()));
    }

    @Test
    @DisplayName("FNC-A-022 5분 안에 1분 통계가 있으면 stat에 가장 최근 통계와 혼잡 단계가 나온다")
    void list_recentStat_returnsLatestWithLevel() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        LocalDateTime thisMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(3), 10.0, 0.2);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(2), 50.5, 0.9);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(1), 120.4, 1.6);

        list(null)
                .andExpect(jsonPath("$[0].stat.statAt").value(thisMinute.minusMinutes(1).atZone(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                .andExpect(jsonPath("$[0].stat.countAvg").value(120.4))
                .andExpect(jsonPath("$[0].stat.density").value(1.6))
                .andExpect(jsonPath("$[0].stat.level").value(3));
    }

    @Test
    @DisplayName("FNC-A-022 recent10m 점마다 밀집도와 혼잡 단계가 있고, 단계는 같은 분 /stats/series(1m)의 level과 같다(결정 0016)")
    void list_recent10m_hasDensityAndLevelMatchingSeries() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        LocalDateTime thisMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(3), 10.0, 0.2);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(2), 50.5, 0.9);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(1), 120.4, 1.6);

        list(null)
                .andExpect(jsonPath("$[0].recent10m", hasSize(3)))
                .andExpect(jsonPath("$[0].recent10m[*].density", contains(0.2, 0.9, 1.6)))
                .andExpect(jsonPath("$[0].recent10m[*].level", contains(1, 2, 3)));

        DateTimeFormatter minute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        String body = mockMvc.perform(get("/api/v1/stats/series")
                        .param("targetType", "ZONE")
                        .param("targetId", String.valueOf(samcheongZoneId))
                        .param("interval", "1m")
                        .param("from", thisMinute.minusMinutes(3).format(minute))
                        .param("to", thisMinute.format(minute)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = jsonMapper.readTree(body).get("items");
        assertThat(items.size()).isEqualTo(3);
        for (int index = 0; index < 3; index++) {
            assertThat(items.get(index).get("level").asInt()).isEqualTo(index + 1);
        }
    }

    @Test
    @DisplayName("FNC-A-022 혼잡 단계는 밀집도 경계(주의 0.7, 위험 1.5)를 따른다")
    void list_statLevel_followsThresholds() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");
        LocalDateTime lastMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        insertStat(samcheongZoneId, lastMinute, 5.0, 0.6999);
        insertStat(sogongZoneId, lastMinute, 5.0, 0.7);
        insertStat(sajikZoneId, lastMinute, 5.0, 1.5);

        list(null)
                .andExpect(jsonPath("$[0].stat.level").value(1))
                .andExpect(jsonPath("$[1].stat.level").value(2))
                .andExpect(jsonPath("$[2].stat.level").value(3));
    }

    @Test
    @DisplayName("FNC-A-022 recent10m은 결과가 들어온 초(sample_sec)가 0인 분을 뺀다(/stats/series 1m의 빈 점과 같게, 결정 0016)")
    void list_recent10m_skipsMinutesWithoutSamples() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        LocalDateTime thisMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(3), 10.0, 0.2);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(2), 0.0, 0.0, 0);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(1), 30.0, 0.4);

        list(null)
                .andExpect(jsonPath("$[0].recent10m[*].countAvg", contains(10.0, 30.0)));
    }

    @Test
    @DisplayName("FNC-A-022 가장 최근 통계가 5분보다 오래됐으면 stat은 null이지만 recent10m에는 남는다")
    void list_staleStat_statNullButRecentKept() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        LocalDateTime thisMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES);
        insertStat(samcheongZoneId, thisMinute.minusMinutes(7), 33.0, 0.5);

        list(null)
                .andExpect(jsonPath("$[0].stat").value(nullValue()))
                .andExpect(jsonPath("$[0].recent10m", hasSize(1)))
                .andExpect(jsonPath("$[0].recent10m[0].countAvg").value(33.0));
    }

    @Test
    @DisplayName("FNC-A-022 recent10m은 최근 10분의 1분 평균 인원을 시각 순서로 최대 10개만 준다")
    void list_recent10m_orderedAndLimited() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        LocalDateTime thisMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES);
        // 12분 전부터 1분 전까지 12개를 넣는다. 10분보다 오래된 것은 빠진다
        for (int ago = 12; ago >= 1; ago--) {
            insertStat(samcheongZoneId, thisMinute.minusMinutes(ago), 100.0 - ago, 0.5);
        }

        String body = list(null).andReturn().getResponse().getContentAsString();
        JsonNode recent = jsonMapper.readTree(body).get(0).get("recent10m");

        assertThat(recent.size()).isBetween(9, 10);
        OffsetDateTime previous = null;
        for (JsonNode point : recent) {
            OffsetDateTime at = OffsetDateTime.parse(point.get("at").asString());
            assertThat(at).isAfterOrEqualTo(OffsetDateTime.now(KST).minusMinutes(10).minusSeconds(5));
            if (previous != null) {
                assertThat(at).isAfter(previous);
            }
            previous = at;
        }
        assertThat(previous.toLocalDateTime()).isEqualTo(thisMinute.minusMinutes(1));
        assertThat(previous.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(recent.get(recent.size() - 1).get("countAvg").asDouble()).isEqualTo(99.0);
    }

    @Test
    @DisplayName("FNC-A-022 통계는 캠이 속한 구역 것만 붙고 통계가 없는 구역의 캠은 null이다")
    void list_stat_onlyOwnZone() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        insertStat(sogongZoneId, LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1), 77.0, 0.3);

        list(null)
                .andExpect(jsonPath("$[0].name").value("삼청동 캠"))
                .andExpect(jsonPath("$[0].stat").value(nullValue()))
                .andExpect(jsonPath("$[0].recent10m", empty()))
                .andExpect(jsonPath("$[1].name").value("소공동 캠"))
                .andExpect(jsonPath("$[1].stat.countAvg").value(77.0))
                .andExpect(jsonPath("$[1].recent10m", hasSize(1)));
    }

    @Test
    @DisplayName("FNC-A-022 regionId가 시·군·구면 그 아래 구역의 캠만 돌려준다")
    void list_filterBySigungu() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");

        list(regionId("11110")) // 종로구
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("삼청동 캠", "사직동 캠")));
        list(regionId("11140")) // 중구
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("소공동 캠"));
    }

    @Test
    @DisplayName("FNC-A-022 regionId가 읍·면·동이면 그 동의 캠만, 시·도면 아래 모든 캠을 돌려준다")
    void list_filterByEmdAndSido() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");

        list(regionId("11110540")) // 삼청동
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("삼청동 캠"));
        list(regionId("11")) // 서울특별시
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    @DisplayName("FNC-A-022 없는 regionId나 캠이 없는 지역이면 빈 목록")
    void list_filterNoMatch_empty() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");

        list(999_999L).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        list(regionId("11140520")) // 소공동(캠 없음)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-A-022 todayEventCount는 오늘 시작한 혼잡·급증 이벤트만 구역별로 세고, 어제 것과 캠 이상은 제외")
    void list_todayEventCount_countsTodayCongestionAndSurgeOnly() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");
        LocalDateTime todayStart = LocalDate.now(KST).atStartOfDay();
        insertEvent(samcheongZoneId, "CONGESTION", todayStart.plusSeconds(1));
        insertEvent(samcheongZoneId, "SURGE", todayStart.plusHours(1));
        insertEvent(samcheongZoneId, "CAM_FAULT", todayStart.plusHours(2));
        insertEvent(samcheongZoneId, "CONGESTION", todayStart.minusSeconds(1));
        insertEvent(sogongZoneId, "CONGESTION", todayStart.minusDays(1));

        list(null)
                .andExpect(jsonPath("$[0].todayEventCount").value(2))
                .andExpect(jsonPath("$[1].todayEventCount").value(0))
                .andExpect(jsonPath("$[2].todayEventCount").value(0));
    }

    @Test
    @DisplayName("FNC-A-022 캠이 늘어도 쿼리 수가 늘지 않는다(캠마다 따로 조회하지 않음)")
    void list_queryCountDoesNotGrowWithCameraCount() throws Exception {
        LocalDateTime lastMinute = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        createCamera(samcheongZoneId, "삼청동 캠");
        insertStat(samcheongZoneId, lastMinute, 10.0, 0.5);
        long withOneCamera = countStatementsOfList();

        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");
        insertStat(sogongZoneId, lastMinute, 20.0, 0.8);
        insertStat(sajikZoneId, lastMinute, 30.0, 1.6);
        long withThreeCameras = countStatementsOfList();

        // 통계가 꺼져 있어 0 == 0으로 통과하는 일이 없도록 쿼리가 실제로 세어졌는지도 본다
        assertThat(withOneCamera).isPositive();
        assertThat(withThreeCameras).isEqualTo(withOneCamera);
    }

    private long countStatementsOfList() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        list(null).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    private long createZone(String name, String polygon) {
        // MockMvc 요청이 끝나면 현재 스레드의 로그인 정보가 비워지므로 부를 때마다 다시 채운다
        AdminTestLogin.loginForDirectCalls();
        return zoneRegisterService.register(name, jsonMapper.readTree(polygon)).id();
    }

    // 실제 분석 결과처럼 1분 내내 결과가 들어온 것(sample_sec=60)으로 넣는다
    private void insertStat(long zoneId, LocalDateTime statAt, double countAvg, double density) {
        insertStat(zoneId, statAt, countAvg, density, 60);
    }

    private void insertStat(long zoneId, LocalDateTime statAt, double countAvg, double density, int sampleSec) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                zoneId,
                statAt,
                countAvg,
                (int) Math.ceil(countAvg),
                (int) Math.floor(countAvg),
                density,
                sampleSec);
    }

    private void insertEvent(long zoneId, String type, LocalDateTime startedAt) {
        jdbcTemplate.update(
                "INSERT INTO event (type, zone_id, started_at, threshold_snapshot) VALUES (?, ?, ?, ?)",
                type,
                zoneId,
                startedAt,
                "{\"warnDensity\":0.7,\"dangerDensity\":1.5}");
    }

    private long createCamera(long zoneId, String name) {
        // MockMvc 요청이 끝나면 현재 스레드의 로그인 정보가 비워지므로 부를 때마다 다시 채운다
        AdminTestLogin.loginForDirectCalls();
        return cameraRegisterService
                .register(new CameraCreateRequest(zoneId, name, CameraSourceType.LIVE, LIVE_URI))
                .id();
    }

    private Long regionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
    }

    private ResultActions list(Long regionId) throws Exception {
        return regionId == null
                ? mockMvc.perform(get(CAMERAS_URL))
                : mockMvc.perform(get(CAMERAS_URL).param("regionId", String.valueOf(regionId)));
    }
}
