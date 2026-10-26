package com.whiteeyes.admin.camera;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.notification.service.NotificationPublisher;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.admin.event.service.CamFaultEventService;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * FNC-A-025 캠 상태 보고 수신. 내부 포트(8082)와 X-Internal-Token으로 들어오는 요청을 MockMvc로 흉내 내고,
 * 분석 서버는 같은 JVM에 띄운 가짜 HTTP 서버로 대신한다.
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class CameraStatusReportTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String CONTEXT_PATH = "/admin";
    private static final int INTERNAL_PORT = 8082;
    private static final String TOKEN = "test-internal-token";

    private static final FakeAnalyzer ANALYZER = FakeAnalyzer.start();

    @DynamicPropertySource
    static void analyzerProperties(DynamicPropertyRegistry registry) {
        registry.add("analyzer.base-url", ANALYZER::baseUrl);
    }

    @AfterAll
    static void stopAnalyzer() {
        ANALYZER.stop();
    }

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    // 이벤트 처리가 실패해도 상태 갱신이 롤백되지 않는지 보려고 실제 객체를 감시(spy)한다
    @MockitoSpyBean
    private CamFaultEventService camFaultEventService;

    @MockitoSpyBean
    private NotificationPublisher notificationPublisher;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long zoneId;

    @BeforeEach
    void prepareZone() {
        AdminTestLogin.loginForDirectCalls();
        ANALYZER.reset();
        clearAll();
        regionLoadService.load(REGION_FILE);
        zoneId = zoneRegisterService
                .register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE))
                .id();
    }

    // camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void cleanUp() {
        AdminTestLogin.clear();
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-025 PREPARING 보고의 진행률이 캠에 반영되고 204이다")
    void report_preparing_updatesProgress() throws Exception {
        long cameraId = insertCamera("PREPARING", 0, null, true);

        report(cameraId, "{\"status\":\"PREPARING\",\"progress\":45,\"errorMessage\":null}")
                .andExpect(status().isNoContent());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("PREPARING");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isEqualTo(45);
        assertThat(ANALYZER.callCount).isZero();
    }

    @Test
    @DisplayName("FNC-A-025 상태 보고를 반영하면 알림 SSE(camera-status)로 상태·진행률·오류 내용을 보낸다")
    void report_notifiesCameraStatus() throws Exception {
        long cameraId = insertCamera("PREPARING", 0, null, true);

        report(cameraId, "{\"status\":\"PREPARING\",\"progress\":45,\"errorMessage\":null}")
                .andExpect(status().isNoContent());
        report(cameraId, "{\"status\":\"ERROR\",\"progress\":45,\"errorMessage\":\"연결 실패\"}")
                .andExpect(status().isNoContent());

        verify(notificationPublisher, times(1))
                .cameraStatusChanged(eq(cameraId), eq(CameraStatus.PREPARING), eq(45), isNull());
        verify(notificationPublisher, times(1))
                .cameraStatusChanged(eq(cameraId), eq(CameraStatus.ERROR), eq(45), eq("연결 실패"));
    }

    @Test
    @DisplayName("FNC-A-025 RUNNING 보고는 progress 100과 함께 오고 그대로 저장되며 이전 오류 내용이 지워진다")
    void report_running_withProgress100_clearsError() throws Exception {
        long cameraId = insertCamera("PREPARING", 95, "분석 모델을 불러올 수 없음", true);

        report(cameraId, "{\"status\":\"RUNNING\",\"progress\":100,\"errorMessage\":null}")
                .andExpect(status().isNoContent());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("RUNNING");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isEqualTo(100);
        assertThat(row.get("ERROR_MESSAGE")).isNull();
        assertThat(ANALYZER.callCount).isZero();
    }

    @Test
    @DisplayName("FNC-A-025 ERROR 보고의 오류 문구(명세·분석 서버가 보내는 4종)가 error_message에 그대로 저장된다")
    void report_error_savesMessageAsIs() throws Exception {
        String[] messages = {
            "영상 파일을 읽을 수 없음", "분석 모델을 불러올 수 없음", "분석 중 오류가 발생함", "캐시 파일을 읽을 수 없음"
        };
        for (String message : messages) {
            long cameraId = insertCamera("PREPARING", 30, null, true);

            report(cameraId, "{\"status\":\"ERROR\",\"progress\":30,\"errorMessage\":\"" + message + "\"}")
                    .andExpect(status().isNoContent());

            Map<String, Object> row = camera(cameraId);
            assertThat(row.get("STATUS")).isEqualTo("ERROR");
            assertThat(row.get("ERROR_MESSAGE")).isEqualTo(message);
            jdbcTemplate.update("DELETE FROM event");
            jdbcTemplate.update("DELETE FROM camera");
        }
    }

    @Test
    @DisplayName("FNC-A-025 progress를 안 보내면 기존 진행률을 유지한다")
    void report_withoutProgress_keepsProgress() throws Exception {
        long cameraId = insertCamera("PREPARING", 45, null, true);

        report(cameraId, "{\"status\":\"PREPARING\"}").andExpect(status().isNoContent());

        assertThat(((Number) camera(cameraId).get("PROGRESS")).intValue()).isEqualTo(45);
    }

    @Test
    @DisplayName("FNC-A-025 run_requested=0인 캠의 RUNNING 보고는 반영하지 않고 분석 서버에 정지를 지시한다")
    void report_runningForStoppedCamera_ignoredAndStopInstructed() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false);

        report(cameraId, "{\"status\":\"RUNNING\",\"progress\":100}").andExpect(status().isNoContent());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("STOPPED");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isZero();
        assertThat(ANALYZER.callCount).isEqualTo(1);
        assertThat(ANALYZER.lastMethod).isEqualTo("POST");
        assertThat(ANALYZER.lastPath).isEqualTo("/analyzer/v1/cameras/" + cameraId + "/stop");
    }

    @Test
    @DisplayName("FNC-A-025 정지 지시가 실패해도(분석 서버 오류) 보고는 204로 끝나고 상태는 그대로이다")
    void report_runningForStoppedCamera_stopFails_stillNoContent() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false);
        ANALYZER.replyWith(500, "{\"code\":\"INTERNAL_ERROR\"}");

        report(cameraId, "{\"status\":\"RUNNING\",\"progress\":100}").andExpect(status().isNoContent());

        assertThat(camera(cameraId).get("STATUS")).isEqualTo("STOPPED");
        assertThat(ANALYZER.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-025 run_requested=0이어도 RUNNING이 아닌 보고(ERROR 등)는 그대로 반영한다")
    void report_nonRunningForStoppedCamera_applied() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false);

        report(cameraId, "{\"status\":\"ERROR\",\"errorMessage\":\"분석 중 오류가 발생함\"}")
                .andExpect(status().isNoContent());

        assertThat(camera(cameraId).get("STATUS")).isEqualTo("ERROR");
        assertThat(ANALYZER.callCount).isZero();
    }

    @Test
    @DisplayName("FNC-A-035 run_requested=1 캠의 ERROR 보고는 캠 이상 이벤트를 열고, 같은 보고가 또 와도 하나만 둔다")
    void report_error_opensCamFaultEventOnce() throws Exception {
        long cameraId = insertCamera("RUNNING", 100, null, true);

        report(cameraId, "{\"status\":\"ERROR\",\"errorMessage\":\"분석 중 오류가 발생함\"}")
                .andExpect(status().isNoContent());
        report(cameraId, "{\"status\":\"ERROR\",\"errorMessage\":\"분석 중 오류가 발생함\"}")
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE type = 'CAM_FAULT' AND camera_id = ? AND zone_id = ?"
                                + " AND ended_at IS NULL",
                        Integer.class,
                        cameraId,
                        zoneId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-035 RUNNING 보고를 받으면 열린 캠 이상 이벤트가 닫힌다")
    void report_running_closesCamFaultEvent() throws Exception {
        long cameraId = insertCamera("ERROR", 30, "분석 중 오류가 발생함", true);
        jdbcTemplate.update(
                "INSERT INTO event (type, zone_id, camera_id, started_at, threshold_snapshot) "
                        + "VALUES ('CAM_FAULT', ?, ?, CURRENT_TIMESTAMP, '{\"timeoutSec\":60}')",
                zoneId,
                cameraId);

        report(cameraId, "{\"status\":\"RUNNING\",\"progress\":100}").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE camera_id = ? AND ended_at IS NULL", Integer.class, cameraId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE camera_id = ? AND ended_at IS NOT NULL", Integer.class, cameraId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-025 ERROR 보고에서 캠 이상 이벤트 저장이 실패해도 상태는 ERROR로 남고 204이다")
    void report_error_eventFailure_keepsStatus() throws Exception {
        long cameraId = insertCamera("RUNNING", 100, null, true);
        doThrow(new IllegalStateException("이벤트 저장 실패"))
                .when(camFaultEventService)
                .openOnError(eq(cameraId), any());

        report(cameraId, "{\"status\":\"ERROR\",\"errorMessage\":\"분석 중 오류가 발생함\"}")
                .andExpect(status().isNoContent());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("ERROR");
        assertThat(row.get("ERROR_MESSAGE")).isEqualTo("분석 중 오류가 발생함");
    }

    @Test
    @DisplayName("FNC-A-025 RUNNING 보고에서 이벤트 닫기가 실패해도 상태는 RUNNING으로 남고 204이다")
    void report_running_eventFailure_keepsStatus() throws Exception {
        long cameraId = insertCamera("ERROR", 30, "분석 중 오류가 발생함", true);
        doThrow(new IllegalStateException("이벤트 닫기 실패"))
                .when(camFaultEventService)
                .closeOnRecovered(eq(cameraId), any(), anyBoolean());

        report(cameraId, "{\"status\":\"RUNNING\",\"progress\":100}").andExpect(status().isNoContent());

        assertThat(camera(cameraId).get("STATUS")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("FNC-A-035 run_requested=0 캠의 ERROR 보고는 상태만 반영하고 캠 이상 이벤트를 열지 않는다")
    void report_errorForStoppedCamera_doesNotOpenCamFaultEvent() throws Exception {
        long cameraId = insertCamera("STOPPED", 0, null, false);

        report(cameraId, "{\"status\":\"ERROR\",\"errorMessage\":\"분석 중 오류가 발생함\"}")
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE camera_id = ?", Integer.class, cameraId))
                .isZero();
    }

    @Test
    @DisplayName("FNC-A-025 없는 캠·삭제된 캠이면 404 NOT_FOUND이다")
    void report_unknownOrDeletedCamera_notFound() throws Exception {
        report(999_999L, "{\"status\":\"RUNNING\",\"progress\":100}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        long deletedId = insertCamera("STOPPED", 0, null, false);
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedId);
        report(deletedId, "{\"status\":\"PREPARING\",\"progress\":10}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(ANALYZER.callCount).isZero();
    }

    @Test
    @DisplayName("FNC-A-025 X-Internal-Token이 없거나 틀리면 401이고 캠은 바뀌지 않는다")
    void report_withoutValidToken_unauthorized() throws Exception {
        long cameraId = insertCamera("PREPARING", 10, null, true);

        send(cameraId, "{\"status\":\"RUNNING\",\"progress\":100}", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        send(cameraId, "{\"status\":\"RUNNING\",\"progress\":100}", TOKEN + "-x")
                .andExpect(status().isUnauthorized());

        assertThat(camera(cameraId).get("STATUS")).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("FNC-A-025 잘못된 본문(없는 status 값·status 없음·진행률 범위 밖)은 400이고 캠은 바뀌지 않는다")
    void report_invalidBody_badRequest() throws Exception {
        long cameraId = insertCamera("PREPARING", 10, null, true);

        report(cameraId, "{\"status\":\"PAUSED\"}").andExpect(status().isBadRequest());
        report(cameraId, "{\"progress\":45}").andExpect(status().isBadRequest());
        report(cameraId, "{\"status\":\"PREPARING\",\"progress\":101}").andExpect(status().isBadRequest());

        Map<String, Object> row = camera(cameraId);
        assertThat(row.get("STATUS")).isEqualTo("PREPARING");
        assertThat(((Number) row.get("PROGRESS")).intValue()).isEqualTo(10);
    }

    private ResultActions report(long cameraId, String body) throws Exception {
        return send(cameraId, body, TOKEN);
    }

    private ResultActions send(long cameraId, String body, String token) throws Exception {
        var request = patch(CONTEXT_PATH + "/internal/v1/cameras/{id}/status", cameraId)
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(r -> {
                    r.setLocalPort(INTERNAL_PORT);
                    return r;
                });
        if (token != null) {
            request.header("X-Internal-Token", token);
        }
        return mockMvc.perform(request);
    }

    // 구역당 캠 1대 규칙 때문에 한 번에 한 대만 만든다(테스트 안에서 반복할 때는 먼저 지운다)
    private long insertCamera(String status, int progress, String errorMessage, boolean runRequested) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, error_message) "
                        + "VALUES (?, '삼청동 캠', 'DUMMY', 'dummy/videos/a.mp4', ?, ?, ?, ?)",
                zoneId,
                runRequested,
                status,
                progress,
                errorMessage);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private Map<String, Object> camera(long cameraId) {
        return jdbcTemplate.queryForMap("SELECT * FROM camera WHERE id = ?", cameraId);
    }
}
