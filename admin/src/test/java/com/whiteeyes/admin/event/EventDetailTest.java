package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.ZoneId;
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
import tools.jackson.databind.json.JsonMapper;

/** 이벤트 상세(FNC-A-037) GET /api/v1/events/{id}. 이벤트·작업 기록 행은 SQL로 직접 넣는다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class EventDetailTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);
    private static final String CONGESTION_SNAPSHOT = "{\"warnDensity\":0.7,\"dangerDensity\":1.5}";

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

    private long zoneId;

    @BeforeEach
    void prepare() {
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        zoneId = zoneRegisterService.register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE)).id();
    }

    // JSON 칼럼에 문자열 매개변수를 그대로 넣으면 H2가 JSON 문자열로 저장하므로 FORMAT JSON으로 넣는다
    // event → camera → zone → region 순으로 외래키가 걸려 있어 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        jdbcTemplate.update("DELETE FROM work_history WHERE target_type = 'EVENT' OR action = 'ZONE_CHANGE'");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-037 목록 필드에 더해 당시 기준값(JSON 객체)과 처리 정보가 담긴다")
    void detail_returnsListFieldsAndExtras() throws Exception {
        createCamera("삼청동 캠");
        long eventId = insertEvent("CONGESTION", null, T0, T0.plusMinutes(18), 3, "1.6200", 486, CONGESTION_SNAPSHOT, "RESOLVED");
        long adminId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM admin_account", Long.class);
        jdbcTemplate.update(
                "UPDATE event SET action_code = 'STAFF', action_note = '출입구 통제', handled_by = ?, handled_at = ? WHERE id = ?",
                adminId,
                T0.plusMinutes(15),
                eventId);

        detail(eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.type").value("CONGESTION"))
                .andExpect(jsonPath("$.zoneName").value("삼청동 1구역"))
                .andExpect(jsonPath("$.regionPath").value("종로구 · 삼청동"))
                .andExpect(jsonPath("$.cameraName").value("삼청동 캠"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-12T09:00:00+09:00"))
                .andExpect(jsonPath("$.endedAt").value("2026-10-12T09:18:00+09:00"))
                .andExpect(jsonPath("$.ongoing").value(false))
                .andExpect(jsonPath("$.durationSec").value(18 * 60))
                .andExpect(jsonPath("$.peakLevel").value(3))
                .andExpect(jsonPath("$.peakValue").value(1.62))
                .andExpect(jsonPath("$.peakCount").value(486))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.thresholdSnapshot.warnDensity").value(0.7))
                .andExpect(jsonPath("$.thresholdSnapshot.dangerDensity").value(1.5))
                .andExpect(jsonPath("$.cameraId").value(nullValue()))
                .andExpect(jsonPath("$.actionCode").value("STAFF"))
                .andExpect(jsonPath("$.actionNote").value("출입구 통제"))
                .andExpect(jsonPath("$.handledBy").value(adminId))
                .andExpect(jsonPath("$.handledAt").value("2026-10-12T09:15:00+09:00"))
                .andExpect(jsonPath("$.histories", hasSize(0)));
    }

    @Test
    @DisplayName("FNC-A-037 처리 이력은 이 이벤트의 EVENT_STATUS 기록만 시각순으로, 이전→이후 상태와 조치 항목을 담는다")
    void detail_histories_onlyThisEventInTimeOrder() throws Exception {
        long eventId = insertEvent("CONGESTION", null, T0, null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "RESOLVED");
        long otherEventId = insertEvent("CONGESTION", null, T0, null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "CHECKED");
        // 일부러 시각 순서를 섞어 넣는다
        insertHistory(eventId, "admin02", T0.plusMinutes(28), "{\"from\":\"CHECKED\",\"to\":\"RESOLVED\",\"actionCode\":\"STAFF\",\"actionNote\":\"인력 배치\"}");
        insertHistory(eventId, "admin02", T0.plusMinutes(15), "{\"from\":\"UNCHECKED\",\"to\":\"CHECKED\"}");
        insertHistory(otherEventId, "admin03", T0.plusMinutes(20), "{\"from\":\"UNCHECKED\",\"to\":\"CHECKED\"}");

        detail(eventId)
                .andExpect(jsonPath("$.histories", hasSize(2)))
                .andExpect(jsonPath("$.histories[0].at").value("2026-10-12T09:15:00+09:00"))
                .andExpect(jsonPath("$.histories[0].loginId").value("admin02"))
                .andExpect(jsonPath("$.histories[0].from").value("UNCHECKED"))
                .andExpect(jsonPath("$.histories[0].to").value("CHECKED"))
                .andExpect(jsonPath("$.histories[0].actionCode").value(nullValue()))
                .andExpect(jsonPath("$.histories[1].at").value("2026-10-12T09:28:00+09:00"))
                .andExpect(jsonPath("$.histories[1].to").value("RESOLVED"))
                .andExpect(jsonPath("$.histories[1].actionCode").value("STAFF"))
                .andExpect(jsonPath("$.histories[1].actionNote").value("인력 배치"));
    }

    @Test
    @DisplayName("FNC-A-037 같은 번호를 쓰는 다른 종류의 대상(ZONE)의 기록은 이력에 섞이지 않는다")
    void detail_histories_ignoresOtherTargetType() throws Exception {
        long eventId = insertEvent("CONGESTION", null, T0, null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "CHECKED");
        jdbcTemplate.update(
                """
                INSERT INTO work_history (actor_type, actor_id, actor_login_id, action, target_type, target_id, detail)
                VALUES ('ADMIN', 1, 'admin01', 'ZONE_CHANGE', 'ZONE', ?, '{"from":"A","to":"B"}')
                """,
                eventId);

        detail(eventId).andExpect(jsonPath("$.histories", hasSize(0)));
    }

    @Test
    @DisplayName("FNC-A-037 진행 중인 이벤트는 ongoing=true, endedAt=null")
    void detail_ongoing() throws Exception {
        long eventId = insertEvent("CONGESTION", null, LocalDateTime.now(KST).minusMinutes(5), null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "UNCHECKED");

        detail(eventId)
                .andExpect(jsonPath("$.ongoing").value(true))
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.actionCode").value(nullValue()))
                .andExpect(jsonPath("$.handledBy").value(nullValue()));
    }

    @Test
    @DisplayName("FNC-A-037 캠 이상은 cameraId와 캠 이름·timeoutSec 기준값을 담고 peak 값은 null")
    void detail_camFault() throws Exception {
        long cameraId = createCamera("삼청동 캠");
        long eventId = insertEvent("CAM_FAULT", cameraId, T0, null, null, null, null, "{\"timeoutSec\":60}", "UNCHECKED");

        detail(eventId)
                .andExpect(jsonPath("$.type").value("CAM_FAULT"))
                .andExpect(jsonPath("$.cameraId").value(cameraId))
                .andExpect(jsonPath("$.cameraName").value("삼청동 캠"))
                .andExpect(jsonPath("$.thresholdSnapshot.timeoutSec").value(60))
                .andExpect(jsonPath("$.peakValue").value(nullValue()))
                .andExpect(jsonPath("$.peakCount").value(nullValue()));
    }

    @Test
    @DisplayName("FNC-A-037 삭제된 구역은 이름 앞에 (삭제됨)이 붙는다")
    void detail_deletedZone() throws Exception {
        long eventId = insertEvent("CONGESTION", null, T0, null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "UNCHECKED");
        jdbcTemplate.update("UPDATE zone SET deleted_at = NOW() WHERE id = ?", zoneId);

        detail(eventId)
                .andExpect(jsonPath("$.zoneName").value("(삭제됨) 삼청동 1구역"))
                .andExpect(jsonPath("$.zoneDeleted").value(true));
    }

    @Test
    @DisplayName("FNC-A-037 playbackAvailable은 혼잡·급증이고 발생이 30일 이내일 때만 true")
    void detail_playbackAvailable() throws Exception {
        LocalDateTime now = LocalDateTime.now(KST);
        long recentCongestion = insertEvent("CONGESTION", null, now.minusDays(29), null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "UNCHECKED");
        long recentSurge = insertEvent("SURGE", null, now.minusDays(1), null, null, "60.0000", 10, "{}", "UNCHECKED");
        long oldCongestion = insertEvent("CONGESTION", null, now.minusDays(31), null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "UNCHECKED");
        long camFault = insertEvent("CAM_FAULT", createCamera("삼청동 캠"), now.minusHours(1), null, null, null, null, "{\"timeoutSec\":60}", "UNCHECKED");

        detail(recentCongestion).andExpect(jsonPath("$.playbackAvailable").value(true));
        detail(recentSurge).andExpect(jsonPath("$.playbackAvailable").value(true));
        detail(oldCongestion).andExpect(jsonPath("$.playbackAvailable").value(false));
        detail(camFault).andExpect(jsonPath("$.playbackAvailable").value(false));
    }

    @Test
    @DisplayName("FNC-A-037 없는 이벤트는 404 NOT_FOUND")
    void detail_unknownEvent_404() throws Exception {
        detail(999999999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-037 로그인하지 않으면 401")
    void detail_anonymous_401() throws Exception {
        long eventId = insertEvent("CONGESTION", null, T0, null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "UNCHECKED");
        mockMvc.perform(get("/api/v1/events/{id}", eventId).with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FNC-A-037 처리 이력이 늘어도 한 번의 조회에 쓰는 SQL 수는 같다")
    void detail_statementCount_independentOfHistoryCount() throws Exception {
        long eventId = insertEvent("CONGESTION", null, T0, null, 2, "0.8000", 10, CONGESTION_SNAPSHOT, "CHECKED");
        insertHistory(eventId, "admin02", T0.plusMinutes(1), "{\"from\":\"UNCHECKED\",\"to\":\"CHECKED\"}");
        long withOne = countStatementsOfDetail(eventId);

        for (int i = 2; i <= 8; i++) {
            insertHistory(eventId, "admin02", T0.plusMinutes(i), "{\"from\":\"CHECKED\",\"to\":\"CHECKED\"}");
        }
        long withEight = countStatementsOfDetail(eventId);

        assertThat(withEight).isEqualTo(withOne);
    }

    private long countStatementsOfDetail(long eventId) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        detail(eventId).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private ResultActions detail(long eventId) throws Exception {
        return mockMvc.perform(get("/api/v1/events/{id}", eventId));
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history WHERE target_type = 'EVENT' OR action = 'ZONE_CHANGE'");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    private long createCamera(String name) {
        AdminTestLogin.loginForDirectCalls();
        return cameraRegisterService
                .register(new CameraCreateRequest(zoneId, name, CameraSourceType.LIVE, LIVE_URI))
                .id();
    }

    private long insertEvent(
            String type,
            Long cameraId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Integer peakLevel,
            String peakValue,
            Integer peakCount,
            String thresholdSnapshot,
            String status) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, camera_id, started_at, ended_at, peak_level, peak_value, peak_count,
                                   threshold_snapshot, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ? FORMAT JSON, ?)
                """,
                type,
                zoneId,
                cameraId,
                startedAt,
                endedAt,
                peakLevel,
                peakValue,
                peakCount,
                thresholdSnapshot,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM event", Long.class);
    }

    private void insertHistory(long eventId, String loginId, LocalDateTime createdAt, String detail) {
        jdbcTemplate.update(
                """
                INSERT INTO work_history (actor_type, actor_id, actor_login_id, action, target_type, target_id, detail, created_at)
                VALUES ('ADMIN', 2, ?, 'EVENT_STATUS', 'EVENT', ?, ? FORMAT JSON, ?)
                """,
                loginId,
                eventId,
                detail,
                createdAt);
    }
}
