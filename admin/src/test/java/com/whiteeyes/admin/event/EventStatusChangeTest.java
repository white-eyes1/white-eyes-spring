package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 처리 상태 변경(FNC-A-038) PATCH /api/v1/events/{id}/status.
 * 작업 기록이 남으므로 AdminTestUserConfig 대신 요청마다 AdminTestLogin.admin()으로 로그인한다(testing.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventStatusChangeTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);
    private static final String CONGESTION_SNAPSHOT = "{\"warnDensity\":0.7,\"dangerDensity\":1.5}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long zoneId;

    // event.handled_by가 admin_account를 가리키므로 테스트 로그인(AdminTestLogin.ADMIN_ID)과 같은 번호의 관리자 행을 둔다
    @BeforeEach
    void prepare() {
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        zoneId = zoneRegisterService.register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE)).id();
        jdbcTemplate.update(
                "INSERT INTO admin_account (id, login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, 'x', '테스트 관리자', 'ADMIN', 'ACTIVE', 0)",
                AdminTestLogin.ADMIN_ID,
                AdminTestLogin.ADMIN_LOGIN_ID);
    }

    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        clearEventsAndAdmin();
        jdbcTemplate.update("DELETE FROM work_history WHERE action = 'ZONE_CHANGE'");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-038 미확인→확인 중: 상태·처리자·처리 시각을 저장하고 변경된 이벤트(상세 모양)를 돌려준다")
    void uncheckedToChecked_savesAndReturnsDetail() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");

        change(eventId, "UNCHECKED", "CHECKED", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.status").value("CHECKED"))
                .andExpect(jsonPath("$.actionCode").value(nullValue()))
                .andExpect(jsonPath("$.handledBy").value(AdminTestLogin.ADMIN_ID))
                .andExpect(jsonPath("$.histories", hasSize(1)))
                .andExpect(jsonPath("$.histories[0].loginId").value(AdminTestLogin.ADMIN_LOGIN_ID))
                .andExpect(jsonPath("$.histories[0].from").value("UNCHECKED"))
                .andExpect(jsonPath("$.histories[0].to").value("CHECKED"));

        Map<String, Object> row = eventRow(eventId);
        assertThat(row.get("STATUS")).isEqualTo("CHECKED");
        assertThat(row.get("HANDLED_BY")).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(row.get("HANDLED_AT")).isNotNull();
        assertThat(row.get("ACTION_CODE")).isNull();
    }

    @Test
    @DisplayName("FNC-A-038 미확인→조치완료 바로 가능: 조치 항목·메모 저장")
    void uncheckedToResolved_directly() throws Exception {
        long eventId = insertEvent("SURGE", null, "UNCHECKED");

        change(eventId, "UNCHECKED", "RESOLVED", "STAFF", "인력 2명 배치")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.actionCode").value("STAFF"))
                .andExpect(jsonPath("$.actionNote").value("인력 2명 배치"))
                .andExpect(jsonPath("$.handledAt").exists());

        Map<String, Object> row = eventRow(eventId);
        assertThat(row.get("ACTION_CODE")).isEqualTo("STAFF");
        assertThat(row.get("ACTION_NOTE")).isEqualTo("인력 2명 배치");
    }

    @Test
    @DisplayName("FNC-A-038 확인 중→조치완료: 작업 기록(EVENT_STATUS)에 이전·이후 상태와 조치 항목이 남고 상세 이력에 나온다")
    void checkedToResolved_recordsWorkHistory() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "CHECKED");

        change(eventId, "CHECKED", "RESOLVED", "OTHER", "현장 점검 완료").andExpect(status().isOk());

        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT * FROM work_history WHERE target_type = 'EVENT' AND target_id = ?", eventId);
        assertThat(history.get("ACTION")).isEqualTo("EVENT_STATUS");
        assertThat(history.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(history.get("ACTOR_ID")).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(history.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        assertThat(history.get("TARGET_NAME")).isEqualTo("삼청동 1구역 혼잡");
        var detail = jsonMapper.readTree(new String((byte[]) history.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("from").asString()).isEqualTo("CHECKED");
        assertThat(detail.get("to").asString()).isEqualTo("RESOLVED");
        assertThat(detail.get("actionCode").asString()).isEqualTo("OTHER");
        assertThat(detail.get("actionNote").asString()).isEqualTo("현장 점검 완료");

        mockMvc.perform(get("/api/v1/events/{id}", eventId).with(AdminTestLogin.admin()))
                .andExpect(jsonPath("$.histories", hasSize(1)))
                .andExpect(jsonPath("$.histories[0].to").value("RESOLVED"))
                .andExpect(jsonPath("$.histories[0].actionCode").value("OTHER"));
    }

    @Test
    @DisplayName("FNC-A-038 종료 전(진행 중) 이벤트와 삭제된 구역의 이벤트도 처리할 수 있다")
    void ongoingEventAndDeletedZone_canBeHandled() throws Exception {
        long ongoing = insertEvent("CONGESTION", null, "UNCHECKED");
        long ended = insertEvent("CONGESTION", T0.plusMinutes(10), "UNCHECKED");
        jdbcTemplate.update("UPDATE zone SET deleted_at = NOW() WHERE id = ?", zoneId);

        change(ongoing, "UNCHECKED", "CHECKED", null, null).andExpect(status().isOk());
        change(ended, "UNCHECKED", "CHECKED", null, null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-038 두 관리자가 동시에 처리하면 한 명만 성공하고 한 명은 409, 작업 기록은 1건")
    void concurrentChange_onlyOneSucceeds() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(concurrentCall(eventId, "CHECKED", ready, go)),
                    pool.submit(concurrentCall(eventId, "RESOLVED", ready, go)));
            ready.await();
            go.countDown();
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        Integer histories = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_history WHERE target_type = 'EVENT' AND target_id = ?",
                Integer.class,
                eventId);
        assertThat(histories).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-038 expectedStatus가 현재 상태와 다르면 409 STATUS_CONFLICT, 아무것도 바뀌지 않는다")
    void expectedStatusMismatch_409() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "CHECKED");

        change(eventId, "UNCHECKED", "RESOLVED", "STAFF", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_CONFLICT"));

        assertNothingChanged(eventId, "CHECKED");
    }

    @Test
    @DisplayName("FNC-A-038 앞으로만 변경 가능: 같은 상태·뒤로 가기·조치완료 이후·미확인으로 바꾸기는 409")
    void invalidTransitions_409() throws Exception {
        long checked = insertEvent("CONGESTION", null, "CHECKED");
        long resolved = insertEvent("CONGESTION", null, "RESOLVED");

        change(checked, "CHECKED", "CHECKED", null, null).andExpect(status().isConflict());
        change(checked, "CHECKED", "UNCHECKED", null, null).andExpect(status().isConflict());
        change(resolved, "RESOLVED", "CHECKED", null, null).andExpect(status().isConflict());
        change(resolved, "RESOLVED", "RESOLVED", "STAFF", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_CONFLICT"));

        assertNothingChanged(checked, "CHECKED");
        assertNothingChanged(resolved, "RESOLVED");
    }

    @Test
    @DisplayName("FNC-A-038 조치완료로 바꿀 때 조치 항목이 없으면 400")
    void resolve_withoutActionCode_400() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");

        change(eventId, "UNCHECKED", "RESOLVED", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertNothingChanged(eventId, "UNCHECKED");
    }

    @Test
    @DisplayName("FNC-A-038 조치 항목이 OTHER면 메모가 필수(비었거나 공백만이면 400)")
    void resolve_otherWithoutNote_400() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");

        change(eventId, "UNCHECKED", "RESOLVED", "OTHER", null).andExpect(status().isBadRequest());
        change(eventId, "UNCHECKED", "RESOLVED", "OTHER", "   ").andExpect(status().isBadRequest());

        assertNothingChanged(eventId, "UNCHECKED");
    }

    @Test
    @DisplayName("FNC-A-038 조치 메모는 200자까지, 201자는 fieldErrors와 함께 400")
    void resolve_noteTooLong_400() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");

        change(eventId, "UNCHECKED", "RESOLVED", "STAFF", "가".repeat(200)).andExpect(status().isOk());

        long other = insertEvent("CONGESTION", null, "UNCHECKED");
        change(other, "UNCHECKED", "RESOLVED", "STAFF", "가".repeat(201))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("actionNote"));
        assertNothingChanged(other, "UNCHECKED");
    }

    @Test
    @DisplayName("FNC-A-038 확인 중으로 바꾸면서 조치 항목·메모를 보내면 400, 새 상태·기대 상태가 비어도 400")
    void invalidBodies_400() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");

        change(eventId, "UNCHECKED", "CHECKED", "STAFF", null).andExpect(status().isBadRequest());
        change(eventId, "UNCHECKED", "CHECKED", null, "메모").andExpect(status().isBadRequest());
        change(eventId, null, "CHECKED", null, null).andExpect(status().isBadRequest());
        change(eventId, "UNCHECKED", null, null, null).andExpect(status().isBadRequest());
        send(eventId, "{\"expectedStatus\":\"UNCHECKED\",\"status\":\"NOPE\"}").andExpect(status().isBadRequest());
        send(eventId, "{\"expectedStatus\":\"UNCHECKED\",\"status\":\"RESOLVED\",\"actionCode\":\"NOPE\"}")
                .andExpect(status().isBadRequest());

        assertNothingChanged(eventId, "UNCHECKED");
    }

    @Test
    @DisplayName("FNC-A-038 없는 이벤트는 404 NOT_FOUND")
    void unknownEvent_404() throws Exception {
        change(999999999L, "UNCHECKED", "CHECKED", null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-038 로그인하지 않으면 401")
    void anonymous_401() throws Exception {
        long eventId = insertEvent("CONGESTION", null, "UNCHECKED");

        mockMvc.perform(patch("/api/v1/events/{id}/status", eventId)
                        .with(anonymous())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"UNCHECKED\",\"status\":\"CHECKED\"}"))
                .andExpect(status().isUnauthorized());

        assertNothingChanged(eventId, "UNCHECKED");
    }

    private Callable<Integer> concurrentCall(long eventId, String newStatus, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await();
            String actionPart = "RESOLVED".equals(newStatus) ? ",\"actionCode\":\"STAFF\"" : "";
            return send(eventId, "{\"expectedStatus\":\"UNCHECKED\",\"status\":\"" + newStatus + "\"" + actionPart + "}")
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
    }

    private ResultActions change(long eventId, String expected, String newStatus, String actionCode, String actionNote)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedStatus", expected);
        body.put("status", newStatus);
        body.put("actionCode", actionCode);
        body.put("actionNote", actionNote);
        return send(eventId, jsonMapper.writeValueAsString(body));
    }

    private ResultActions send(long eventId, String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/events/{id}/status", eventId)
                .with(AdminTestLogin.admin())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    // 실패한 요청은 이벤트도 작업 기록도 바꾸지 않아야 한다
    private void assertNothingChanged(long eventId, String expectedStatus) {
        Map<String, Object> row = eventRow(eventId);
        assertThat(row.get("STATUS")).isEqualTo(expectedStatus);
        assertThat(row.get("HANDLED_BY")).isNull();
        Integer histories = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_history WHERE target_type = 'EVENT' AND target_id = ?",
                Integer.class,
                eventId);
        assertThat(histories).isZero();
    }

    private Map<String, Object> eventRow(long eventId) {
        return jdbcTemplate.queryForMap("SELECT * FROM event WHERE id = ?", eventId);
    }

    private void clearAll() {
        clearEventsAndAdmin();
        jdbcTemplate.update("DELETE FROM work_history WHERE action = 'ZONE_CHANGE'");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    // event.handled_by → admin_account 순서로 지운다
    private void clearEventsAndAdmin() {
        jdbcTemplate.update("DELETE FROM work_history WHERE target_type = 'EVENT'");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM admin_account WHERE id = ?", AdminTestLogin.ADMIN_ID);
    }

    // JSON 칼럼에는 FORMAT JSON으로 넣어야 객체로 저장된다(EventDetailTest와 같음)
    private long insertEvent(String type, LocalDateTime endedAt, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, started_at, ended_at, peak_level, peak_value, peak_count,
                                   threshold_snapshot, status)
                VALUES (?, ?, ?, ?, 2, 0.8, 10, ? FORMAT JSON, ?)
                """,
                type,
                zoneId,
                T0,
                endedAt,
                CONGESTION_SNAPSHOT,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM event", Long.class);
    }
}
