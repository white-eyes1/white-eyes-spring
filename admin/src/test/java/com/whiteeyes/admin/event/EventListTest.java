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
import com.whiteeyes.admin.support.ThreadStatementCounter;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
// 요청을 보낸 스레드가 실행한 쿼리만 센다. 같은 앱의 예약 작업(캠 이상 감지 등)이 다른 스레드에서 도는 쿼리는 세지 않는다
@SpringBootTest(
        properties =
                "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.whiteeyes.admin.support.ThreadStatementCounter")
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
    @DisplayName("FNC-A-036 행에 구역 이름·구 동 경로·진행 중 여부·지속 시간·최고 값이 담긴다(혼잡의 캠 이름은 null)")
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
                .andExpect(jsonPath("$.items[0].cameraName").value(nullValue()))
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
        LocalDateTime startedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(30);
        insertEvent("CONGESTION", samcheongZoneId, null, startedAt, null, 2, "0.8000", 10, "UNCHECKED");

        String body = list().andExpect(jsonPath("$.items[0].ongoing").value(true))
                .andExpect(jsonPath("$.items[0].endedAt").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long durationSec = jsonMapper.readTree(body).get("items").get(0).get("durationSec").asLong();
        assertThat(durationSec).isBetween(30 * 60L - 2, 31 * 60L);
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
    @DisplayName("FNC-A-036 캠 이상은 이벤트의 캠 이름, 혼잡·급증은 구역에 캠이 있어도 null")
    void list_cameraName_onlyCamFault() throws Exception {
        long samcheongCamera = createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = NOW() WHERE id = ?", samcheongCamera);
        insertEvent("CAM_FAULT", samcheongZoneId, samcheongCamera, T0, null, null, null, null, "UNCHECKED");
        insertEvent("CONGESTION", sogongZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("SURGE", sogongZoneId, null, T0.plusMinutes(3), null, 2, "1.5000", 10, "UNCHECKED");
        insertEvent("CONGESTION", sajikZoneId, null, T0.plusMinutes(2), null, 2, "0.8000", 10, "UNCHECKED");

        list().andExpect(jsonPath("$.items[3].cameraName").value("삼청동 캠"))
                .andExpect(jsonPath("$.items[2].cameraName").value(nullValue()))
                .andExpect(jsonPath("$.items[1].cameraName").value(nullValue()))
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
    @DisplayName("FNC-A-036 overlap=true면 기간 앞에서 시작해 기간 안에서 이어진 이벤트도 포함하고, 기간 앞에서 끝난 것·기간 뒤에 시작한 것은 뺀다(#198)")
    void list_overlap_includesEventsContinuingIntoPeriod() throws Exception {
        // 기간: 10:00 이상 12:00 미만(T0는 09:00)
        // 07:00 시작, 08:30 종료: 기간 앞에서 끝났다 → overlap에서도 뺀다
        insertEvent("CONGESTION", samcheongZoneId, null, T0.minusHours(2), T0.minusMinutes(30), 2, "0.8000", 10, "UNCHECKED");
        // 08:00 시작, 10:30 종료: 기간 앞에서 시작해 기간 안에서 끝났다 → overlap에서 포함
        long endsAfterFrom = insertEvent("CONGESTION", samcheongZoneId, null, T0.minusHours(1), T0.plusMinutes(90), 2, "0.8000", 10, "UNCHECKED");
        // 06:00 시작, 진행 중: 기간 앞에서 시작해 이어지는 중이다 → overlap에서 포함
        long ongoing = insertEvent("SURGE", samcheongZoneId, null, T0.minusHours(3), null, null, "150.0000", 10, "UNCHECKED");
        // 11:00 시작, 11:05 종료: 기간 안 → 기본·overlap 모두 포함
        long inside = insertEvent("CONGESTION", samcheongZoneId, null, T0.plusHours(2), T0.plusHours(2).plusMinutes(5), 2, "0.8000", 10, "UNCHECKED");
        // 12:00 시작(to 정각): 기간 끝에 시작했다 → 기본·overlap 모두 뺀다
        insertEvent("CONGESTION", samcheongZoneId, null, T0.plusHours(3), null, 2, "0.8000", 10, "UNCHECKED");
        // 08:00 시작, 10:00 종료(from 정각): 끝이 정확히 from이라 겹치지 않는다 → overlap에서 뺀다
        insertEvent("CONGESTION", samcheongZoneId, null, T0.minusHours(1), T0.plusHours(1), 2, "0.8000", 10, "UNCHECKED");

        // 기본(overlap 없음)은 발생 시각이 기간 안인 것만: inside 하나(발생이 10:00~12:00 사이인 것)
        list(param("from", "2026-10-12T10:00:00+09:00"), param("to", "2026-10-12T12:00:00+09:00"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(inside));

        // overlap=true: 기간 앞에서 시작해 이어진 것(endsAfterFrom, ongoing)과 기간 안(inside)만 준다. 08:30에 끝난 것, 10:00에 끝난 것,
        // 12:00에 시작한 것은 뺀다. 발생 시각 내림차순
        list(param("from", "2026-10-12T10:00:00+09:00"), param("to", "2026-10-12T12:00:00+09:00"), param("overlap", "true"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) inside, (int) endsAfterFrom, (int) ongoing)));

        // 요약도 같은 조건을 쓴다
        mockMvc.perform(get(EVENTS_URL + "/summary")
                        .param("from", "2026-10-12T10:00:00+09:00")
                        .param("to", "2026-10-12T12:00:00+09:00")
                        .param("overlap", "true"))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @DisplayName("FNC-A-036 overlap은 true·false만 받고 잘못된 값은 400이다")
    void list_overlap_invalid_400() throws Exception {
        list(param("overlap", "maybe")).andExpect(status().isBadRequest());
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
    @DisplayName("FNC-A-036 다른 오프셋(Z)으로 준 기간도 한국 시간으로 바꿔 거른다")
    void list_period_otherOffsetConvertedToKst() throws Exception {
        // 2026-10-12T00:00Z = 한국 09:00
        insertEvent("CONGESTION", samcheongZoneId, null, T0.minusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        long hit = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");

        list(param("from", "2026-10-12T00:00:00Z"), param("to", "2026-10-12T01:00:00Z"))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) hit)));
    }

    @Test
    @DisplayName("FNC-A-036 삭제된 캠 이름으로도 검색된다")
    void list_keyword_matchesDeletedCameraName() throws Exception {
        long deletedCamera = createCamera(samcheongZoneId, "철거된 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = NOW() WHERE id = ?", deletedCamera);
        createCamera(sogongZoneId, "소공동 캠");
        long hit = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", sogongZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");

        list(param("keyword", "철거")).andExpect(jsonPath("$.items[*].id").value(contains((int) hit)));
    }

    @Test
    @DisplayName("FNC-A-036 keyword와 지역·구역을 함께 주면 둘 다 맞는 구역만 본다")
    void list_keywordWithRegionOrZone_intersection() throws Exception {
        long samcheong = insertEvent("CONGESTION", samcheongZoneId, null, T0, null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", sajikZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", sogongZoneId, null, T0.plusMinutes(2), null, 2, "0.8000", 10, "UNCHECKED");

        // 종로구 안에서 이름에 '삼청'
        list(param("regionId", String.valueOf(regionId("종로구"))), param("keyword", "삼청"))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) samcheong)));
        // 중구 안에는 '삼청'이 없다
        list(param("regionId", String.valueOf(regionId("중구"))), param("keyword", "삼청"))
                .andExpect(jsonPath("$.items", empty()));
        list(param("zoneId", String.valueOf(sogongZoneId)), param("keyword", "삼청"))
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    @DisplayName("FNC-A-036 구역에 캠이 여럿이어도 혼잡은 캠 이름이 null, 캠 번호가 없는 캠 이상도 null")
    void list_cameraName_nullWhenNoEventCamera() throws Exception {
        long oldCamera = createCamera(samcheongZoneId, "옛 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = NOW() WHERE id = ?", oldCamera);
        createCamera(samcheongZoneId, "새 캠");
        insertEvent("CAM_FAULT", samcheongZoneId, null, T0, null, null, null, null, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, null, T0.plusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");

        list().andExpect(jsonPath("$.items[0].cameraName").value(nullValue()))
                .andExpect(jsonPath("$.items[1].cameraName").value(nullValue()));
    }

    @Test
    @DisplayName("FNC-A-036 캠 이상 이벤트가 섞여 있어도 SQL 수는 이벤트 수와 무관하다")
    void list_statementCount_withCamFault() throws Exception {
        long camera = createCamera(samcheongZoneId, "삼청동 캠");
        insertEvent("CAM_FAULT", samcheongZoneId, camera, T0, null, null, null, null, "UNCHECKED");
        long withOne = countStatementsOfList();

        long other = createCamera(sogongZoneId, "소공동 캠");
        for (int i = 1; i <= 5; i++) {
            insertEvent("CAM_FAULT", sogongZoneId, other, T0.plusMinutes(i), null, null, null, null, "UNCHECKED");
        }
        assertThat(countStatementsOfList()).isEqualTo(withOne);
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
        ThreadStatementCounter.reset();
        list().andExpect(status().isOk());
        return ThreadStatementCounter.count();
    }

    @Test
    @DisplayName("FNC-A-036 SQL 수 시험은 다른 스레드(예약 작업)가 실행한 쿼리를 세지 않는다")
    void statementCounter_ignoresOtherThreads() throws Exception {
        ThreadStatementCounter.reset();
        Thread scheduler = new Thread(this::runEventCountQuery, "scheduler-like");
        scheduler.start();
        scheduler.join();
        assertThat(ThreadStatementCounter.count()).isZero();

        runEventCountQuery();
        assertThat(ThreadStatementCounter.count()).isEqualTo(1);
    }

    private void runEventCountQuery() {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.createQuery("select count(e) from Event e", Long.class).getSingleResult();
        }
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
