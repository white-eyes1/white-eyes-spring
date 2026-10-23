package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** 이벤트 목록(FNC-A-036) GET /api/v1/events. 이벤트 행은 처리 상태·종류가 다양해야 해서 SQL로 직접 넣는다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class EventListTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String EVENTS_URL = "/api/v1/events";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";

    // 삼청동(종로구) / 소공동(중구) / 사직동(종로구) 안의 사각형
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final String SAJIK_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[126.96,37.56],[126.962,37.56],[126.962,37.562],[126.96,37.562],[126.96,37.56]]]}";

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);

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
    void prepare() {
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZoneId = createZone("삼청동 1구역", SAMCHEONG_SQUARE);
        sogongZoneId = createZone("소공동 1구역", SOGONG_SQUARE);
        sajikZoneId = createZone("사직동 1구역", SAJIK_SQUARE);
    }

    // event → camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-036 이벤트가 없으면 빈 페이지")
    void list_noEvents_emptyPage() throws Exception {
        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("FNC-A-036 행에 구역 이름·구 동 경로·캠 이름·진행 중 여부·지속 시간·최고 값이 담긴다")
    void list_returnsRowFields() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        long eventId = insertEvent("CONGESTION", samcheongZoneId, null, T0, T0.plusMinutes(10), 3, "1.6400", 48, "CHECKED");

        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(eventId))
                .andExpect(jsonPath("$.items[0].type").value("CONGESTION"))
                .andExpect(jsonPath("$.items[0].zoneId").value(samcheongZoneId))
                .andExpect(jsonPath("$.items[0].zoneName").value("삼청동 1구역"))
                .andExpect(jsonPath("$.items[0].regionPath").value("종로구 · 삼청동"))
                .andExpect(jsonPath("$.items[0].cameraName").value("삼청동 캠"))
                .andExpect(jsonPath("$.items[0].zoneDeleted").value(false))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-12T09:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].endedAt").value("2026-10-12T09:10:00+09:00"))
                .andExpect(jsonPath("$.items[0].ongoing").value(false))
                .andExpect(jsonPath("$.items[0].durationSec").value(600))
                .andExpect(jsonPath("$.items[0].peakLevel").value(3))
                .andExpect(jsonPath("$.items[0].peakValue").value(1.64))
                .andExpect(jsonPath("$.items[0].peakCount").value(48))
                .andExpect(jsonPath("$.items[0].status").value("CHECKED"));
    }

    @Test
    @DisplayName("FNC-A-036 started_at 내림차순, 같으면 번호가 큰 것이 먼저")
    void list_ordersByStartedAtDesc() throws Exception {
        long older = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long newer = insertEvent("CONGESTION", sogongZoneId, null, T0.plusHours(1), null, 2, "0.8000", 10, "UNCHECKED");
        long sameTimeLater =
                insertEvent("CONGESTION", sajikZoneId, null, T0.plusHours(1), null, 2, "0.8000", 10, "UNCHECKED");

        list()
                .andExpect(jsonPath("$.items[*].id").value(contains((int) sameTimeLater, (int) newer, (int) older)));
    }

    @Test
    @DisplayName("FNC-A-036 진행 중인 이벤트는 ongoing=true이고 지속 시간이 현재 시각까지 계산된다")
    void list_ongoingEvent_durationUntilNow() throws Exception {
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(30);
        insertEvent("CONGESTION", samcheongZoneId, null, startedAt, null, 2, "0.8000", 10, "UNCHECKED");

        String body = list().andExpect(jsonPath("$.items[0].ongoing").value(true))
                .andExpect(jsonPath("$.items[0].endedAt").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long durationSec = jsonMapper.readTree(body).get("items").get(0).get("durationSec").asLong();
        assertThat(durationSec).isBetween(30 * 60L, 31 * 60L);
    }

    @Test
    @DisplayName("FNC-A-036 삭제된 구역은 이름 앞에 (삭제됨)이 붙고 zoneDeleted=true")
    void list_deletedZone_markedDeleted() throws Exception {
        insertEvent("CONGESTION", sajikZoneId, null, T0, T0.plusMinutes(5), 2, "0.8000", 10, "RESOLVED");
        jdbcTemplate.update("UPDATE zone SET deleted_at = NOW() WHERE id = ?", sajikZoneId);

        list().andExpect(jsonPath("$.items[0].zoneName").value("(삭제됨) 사직동 1구역"))
                .andExpect(jsonPath("$.items[0].zoneDeleted").value(true))
                .andExpect(jsonPath("$.items[0].regionPath").value("종로구 · 사직동"));
    }

    @Test
    @DisplayName("FNC-A-036 캠 이상은 이벤트의 캠, 혼잡은 구역의 캠 이름을 보인다. 캠이 없으면 null")
    void list_cameraName_fromEventOrZone() throws Exception {
        long samcheongCamera = createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = NOW() WHERE id = ?", samcheongCamera);
        insertEvent("CAM_FAULT", samcheongZoneId, samcheongCamera, T0, null, null, null, null, "UNCHECKED");
        insertEvent("CONGESTION", sogongZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", sajikZoneId, null, T0.plusMinutes(2), null, 2, "0.8000", 10, "UNCHECKED");

        list().andExpect(jsonPath("$.items[2].cameraName").value("삼청동 캠"))
                .andExpect(jsonPath("$.items[1].cameraName").value("소공동 캠"))
                .andExpect(jsonPath("$.items[0].cameraName").value(nullValue()));
    }

    @Test
    @DisplayName("FNC-A-036 기간은 from 이상 to 미만으로 거른다")
    void list_period_halfOpen() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, null, T0.minusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        long atFrom = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long inside = insertEvent("CONGESTION", samcheongZoneId, null, T0.plusHours(1), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, null, T0.plusHours(2), null, 2, "0.8000", 10, "UNCHECKED");

        list(param("from", "2026-10-12T09:00:00+09:00"), param("to", "2026-10-12T11:00:00+09:00"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) inside, (int) atFrom)));
    }

    @Test
    @DisplayName("FNC-A-036 from만 또는 to만 주면 그쪽만 거른다")
    void list_period_oneSided() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, null, T0.plusHours(5), null, 2, "0.8000", 10, "UNCHECKED");

        list(param("from", "2026-10-12T12:00:00+09:00")).andExpect(jsonPath("$.totalElements").value(1));
        list(param("to", "2026-10-12T12:00:00+09:00")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("FNC-A-036 zoneId로 그 구역 이벤트만 본다")
    void list_byZone() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long sogong = insertEvent("CONGESTION", sogongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");

        list(param("zoneId", String.valueOf(sogongZoneId)))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) sogong)));
    }

    @Test
    @DisplayName("FNC-A-036 regionId는 구·동 아래 소속 구역을 모두 포함한다")
    void list_byRegion_includesChildZones() throws Exception {
        long samcheong = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long sajik = insertEvent("CONGESTION", sajikZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        long sogong = insertEvent("CONGESTION", sogongZoneId, null, T0.plusMinutes(2), null, 2, "0.8000", 10, "UNCHECKED");

        list(param("regionId", String.valueOf(regionId("종로구"))))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) sajik, (int) samcheong)));
        list(param("regionId", String.valueOf(regionId("소공동"))))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) sogong)));
    }

    @Test
    @DisplayName("FNC-A-036 types는 여러 개를 함께 줄 수 있다")
    void list_byTypes_multiple() throws Exception {
        long congestion = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long surge = insertEvent("SURGE", samcheongZoneId, null, T0.plusMinutes(1), null, null, "55.0000", 30, "UNCHECKED");
        insertEvent("CAM_FAULT", samcheongZoneId, null, T0.plusMinutes(2), null, null, null, null, "UNCHECKED");

        list(param("types", "CONGESTION"), param("types", "SURGE"))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) surge, (int) congestion)));
        list(param("types", "CONGESTION,CAM_FAULT")).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("FNC-A-036 statuses는 여러 개를 함께 줄 수 있다")
    void list_byStatuses_multiple() throws Exception {
        long unchecked = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long checked = insertEvent("CONGESTION", samcheongZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "CHECKED");
        insertEvent("CONGESTION", samcheongZoneId, null, T0.plusMinutes(2), null, 2, "0.8000", 10, "RESOLVED");

        list(param("statuses", "UNCHECKED"), param("statuses", "CHECKED"))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) checked, (int) unchecked)));
    }

    @Test
    @DisplayName("FNC-A-036 keyword는 구역 이름·캠 이름·구 이름·동 이름 부분 일치")
    void list_byKeyword_zoneCameraRegionNames() throws Exception {
        createCamera(samcheongZoneId, "북쪽 정문 캠");
        long samcheong = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long sogong = insertEvent("CONGESTION", sogongZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        long sajik = insertEvent("CONGESTION", sajikZoneId, null, T0.plusMinutes(2), null, 2, "0.8000", 10, "UNCHECKED");

        // 구역 이름
        list(param("keyword", "소공동 1")).andExpect(jsonPath("$.items[*].id").value(contains((int) sogong)));
        // 캠 이름
        list(param("keyword", "정문")).andExpect(jsonPath("$.items[*].id").value(contains((int) samcheong)));
        // 소속 동 이름
        list(param("keyword", "사직")).andExpect(jsonPath("$.items[*].id").value(contains((int) sajik)));
        // 소속 구 이름: 종로구의 삼청동·사직동 구역
        list(param("keyword", "종로"))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) sajik, (int) samcheong)));
        // 일치하는 것이 없으면 빈 페이지
        list(param("keyword", "없는이름")).andExpect(jsonPath("$.items", empty()));
    }

    @Test
    @DisplayName("FNC-A-036 keyword는 삭제된 구역의 이름으로도 찾는다")
    void list_byKeyword_deletedZone() throws Exception {
        long sajik = insertEvent("CONGESTION", sajikZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        jdbcTemplate.update("UPDATE zone SET deleted_at = NOW() WHERE id = ?", sajikZoneId);

        list(param("keyword", "사직동 1구역")).andExpect(jsonPath("$.items[*].id").value(contains((int) sajik)));
    }

    @Test
    @DisplayName("FNC-A-036 필터 조합: 기간 + 지역 + 종류 + 상태 + 검색어")
    void list_combinedFilters() throws Exception {
        long hit = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 3, "1.6000", 40, "CHECKED");
        insertEvent("CONGESTION", samcheongZoneId, null, T0.plusDays(1), null, 3, "1.6000", 40, "CHECKED"); // 기간 밖
        insertEvent("CONGESTION", sogongZoneId, null, T0, null, 3, "1.6000", 40, "CHECKED"); // 지역 밖
        insertEvent("SURGE", samcheongZoneId, null, T0, null, null, "60.0000", 40, "CHECKED"); // 종류 밖
        insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 3, "1.6000", 40, "UNCHECKED"); // 상태 밖

        list(
                        param("from", "2026-10-12T00:00:00+09:00"),
                        param("to", "2026-10-13T00:00:00+09:00"),
                        param("regionId", String.valueOf(regionId("종로구"))),
                        param("types", "CONGESTION"),
                        param("statuses", "CHECKED"),
                        param("keyword", "삼청"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) hit)));
    }

    @Test
    @DisplayName("FNC-A-036 page·size로 나눠 주고 totalElements·totalPages를 센다")
    void list_paging() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertEvent("CONGESTION", samcheongZoneId, null, T0.plusMinutes(i), null, 2, "0.8000", 10, "UNCHECKED");
        }

        list(param("page", "1"), param("size", "2"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        list(param("page", "2"), param("size", "2")).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @DisplayName("FNC-A-036 size는 1~100, page는 0 이상이 아니면 400 INVALID_INPUT")
    void list_invalidPaging_400() throws Exception {
        list(param("size", "101")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        list(param("size", "0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        list(param("page", "-1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        list(param("size", "100")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-036 알 수 없는 종류·상태 값은 400")
    void list_unknownTypeOrStatus_400() throws Exception {
        list(param("types", "WEATHER")).andExpect(status().isBadRequest());
        list(param("statuses", "DONE")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("FNC-A-036 regionId와 zoneId를 함께 주면 400, from이 to 이상이어도 400")
    void list_conflictingScopeOrPeriod_400() throws Exception {
        list(param("regionId", String.valueOf(regionId("종로구"))), param("zoneId", String.valueOf(samcheongZoneId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        list(param("from", "2026-10-12T11:00:00+09:00"), param("to", "2026-10-12T11:00:00+09:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-036 없는 지역 번호로 거르면 404 REGION_NOT_FOUND")
    void list_unknownRegion_404() throws Exception {
        list(param("regionId", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-036 로그인하지 않으면 401")
    void list_anonymous_401() throws Exception {
        mockMvc.perform(get(EVENTS_URL).with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-036 이벤트가 늘어도 한 번의 조회에 쓰는 SQL 수는 같다")
    void list_statementCount_independentOfEventCount() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long withOne = countStatementsOfList();

        for (int i = 1; i <= 9; i++) {
            insertEvent("CONGESTION", i % 2 == 0 ? sogongZoneId : sajikZoneId, null, T0.plusMinutes(i), null, 2, "0.8000", 10, "UNCHECKED");
        }
        long withTen = countStatementsOfList();

        assertThat(withTen).isEqualTo(withOne);
    }

    private long countStatementsOfList() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        list().andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private ResultActions list(ParamPair... params) throws Exception {
        MockHttpServletRequestBuilder request = get(EVENTS_URL);
        for (ParamPair pair : params) {
            request.param(pair.name(), pair.value());
        }
        return mockMvc.perform(request);
    }

    private static ParamPair param(String name, String value) {
        return new ParamPair(name, value);
    }

    private record ParamPair(String name, String value) {}

    private long regionId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = ?", Long.class, name);
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

    private long createCamera(long zoneId, String name) {
        AdminTestLogin.loginForDirectCalls();
        return cameraRegisterService
                .register(new CameraCreateRequest(zoneId, name, CameraSourceType.LIVE, LIVE_URI))
                .id();
    }

    private long insertEvent(
            String type,
            long zoneId,
            Long cameraId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Integer peakLevel,
            String peakValue,
            Integer peakCount,
            String status) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, camera_id, started_at, ended_at, peak_level, peak_value, peak_count,
                                   threshold_snapshot, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', ?)
                """,
                type,
                zoneId,
                cameraId,
                startedAt,
                endedAt,
                peakLevel,
                peakValue,
                peakCount,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM event", Long.class);
    }
}
