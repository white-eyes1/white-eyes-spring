package com.whiteeyes.admin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.notification.service.NotificationBroadcaster;
import com.whiteeyes.admin.notification.service.NotificationPublisher;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.event.entity.EventType;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 알림 스트림(FNC-A-048). 검수 기준: 새 이벤트가 생기면 열려 있는 모든 관리자 화면에 알림이 간다.
 * 연결 직후 현재 미확인 수를 한 번 보내고, 이벤트 생성·상태 변경·캠 상태 변경을 SSE로 보낸다.
 */
@Import(AdminTestUserConfig.class)
// 끝나지 않는 SSE 요청이 DB 연결을 붙들지 않도록 open-in-view를 끈다(StatStreamTest와 같음)
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
class NotificationStreamTest {

    private static final String STREAM_URL = "/api/v1/notifications/stream";
    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],"
                    + "[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);
    private static final String CONGESTION_SNAPSHOT = "{\"warnDensity\":0.7,\"dangerDensity\":1.5}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationBroadcaster broadcaster;

    @Autowired
    private NotificationPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long zoneId;

    @BeforeEach
    void setUp() {
        broadcaster.stop();
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        zoneId = zoneRegisterService.register("삼청동 1구역", jsonMapper.readTree(SAMCHEONG_SQUARE)).id();
    }

    @AfterEach
    void tearDown() {
        AdminTestLogin.clear();
        broadcaster.stop();
        clearAll();
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

    @Test
    @DisplayName("FNC-A-048 연결하면 text/event-stream으로 답하고 바로 현재 미확인 수를 한 번 보낸다")
    void sendsUncheckedCountRightAfterConnecting() throws Exception {
        insertEvent("UNCHECKED");
        insertEvent("UNCHECKED");
        insertEvent("CHECKED");

        MvcResult stream = mockMvc.perform(get(STREAM_URL))
                .andExpect(request().asyncStarted())
                .andReturn();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream))
                .contains("event:unchecked-count")
                .contains("\"count\":2"));
        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-048 새 이벤트 알림이 연결된 모든 화면에 도착하고 형식이 {id,type,zoneName,level,startedAt}이다")
    void deliversEventCreatedToEverySubscriber() {
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter second = new RecordingEmitter();
        broadcaster.subscribe(first);
        broadcaster.subscribe(second);
        long eventId = insertEvent("UNCHECKED", "CONGESTION", 3);

        publisher.eventCreated(eventId, EventType.CONGESTION, zoneId, 3, T0);

        for (RecordingEmitter emitter : List.of(first, second)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emitter.text())
                    .contains("event:event-created")
                    .contains("\"id\":" + eventId)
                    .contains("\"type\":\"CONGESTION\"")
                    .contains("\"zoneName\":\"삼청동 1구역\"")
                    .contains("\"level\":3")
                    .contains("\"startedAt\":\"2026-10-12T09:00:00+09:00\""));
        }
    }

    @Test
    @DisplayName("FNC-A-048 event-created의 startedAt은 Asia/Seoul 기준 +09:00 형식으로 나간다")
    void startedAtIsSentWithSeoulOffset() {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.subscribe(emitter);
        long eventId = insertEvent("UNCHECKED", "CONGESTION", 2);

        publisher.eventCreated(eventId, EventType.CONGESTION, zoneId, 2, LocalDateTime.of(2026, 1, 5, 23, 59, 30));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emitter.text())
                .contains("\"startedAt\":\"2026-01-05T23:59:30+09:00\"")
                .doesNotContain("Z\"")
                .doesNotContain("[Asia/Seoul]"));
    }

    @Test
    @DisplayName("FNC-A-048 급증·캠 이상 이벤트의 level은 null")
    void levelIsNullForSurgeAndCamFault() {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.subscribe(emitter);
        long eventId = insertEvent("UNCHECKED", "SURGE", null);

        publisher.eventCreated(eventId, EventType.SURGE, zoneId, null, T0);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emitter.text())
                .contains("\"type\":\"SURGE\"")
                .contains("\"level\":null"));
    }

    @Test
    @DisplayName("FNC-A-048 미확인 수 알림은 그 시점의 UNCHECKED 건수를 보낸다")
    void sendsCurrentUncheckedCount() {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.subscribe(emitter);
        insertEvent("UNCHECKED");
        insertEvent("UNCHECKED");
        insertEvent("UNCHECKED");
        insertEvent("RESOLVED");

        publisher.uncheckedCountChanged();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emitter.text())
                .contains("event:unchecked-count")
                .contains("\"count\":3"));
    }

    @Test
    @DisplayName("FNC-A-048 캠 상태 알림 형식이 {cameraId,status,progress,errorMessage}이다")
    void deliversCameraStatus() {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.subscribe(emitter);

        publisher.cameraStatusChanged(7L, CameraStatus.PREPARING, 40, null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emitter.text())
                .contains("event:camera-status")
                .contains("\"cameraId\":7")
                .contains("\"status\":\"PREPARING\"")
                .contains("\"progress\":40")
                .contains("\"errorMessage\":null"));
    }

    @Test
    @DisplayName("FNC-A-048 30초마다 보내는 연결 유지 주석이 구독자에게 간다")
    void sendsKeepAliveComment() {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.subscribe(emitter);

        broadcaster.sendHeartbeatNow();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(emitter.text()).contains(":keepalive"));
    }

    @Test
    @DisplayName("FNC-A-048 끊긴 구독자는 목록에서 빠지고 다른 구독자에게는 계속 간다")
    void dropsBrokenSubscriber() {
        RecordingEmitter healthy = new RecordingEmitter();
        SseEmitter broken = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("연결 끊김");
            }
        };
        broadcaster.subscribe(broken);
        broadcaster.subscribe(healthy);

        publisher.cameraStatusChanged(1L, CameraStatus.RUNNING, 100, null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(healthy.text()).contains("event:camera-status");
            assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("FNC-A-048 쓰기에서 막힌 구독자가 있어도 다른 구독자에게 바로 전달된다")
    void blockedSubscriberDoesNotDelayOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SseEmitter blocked = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        RecordingEmitter healthy = new RecordingEmitter();
        broadcaster.subscribe(blocked);
        broadcaster.subscribe(healthy);

        publisher.cameraStatusChanged(2L, CameraStatus.ERROR, 0, "연결 실패");

        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(healthy.text())
                    .contains("\"status\":\"ERROR\"")
                    .contains("\"errorMessage\":\"연결 실패\""));
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("FNC-A-048 GET /api/v1/events/unchecked-count가 {count}를 돌려준다")
    void uncheckedCountApi() throws Exception {
        insertEvent("UNCHECKED");
        insertEvent("CHECKED");
        insertEvent("UNCHECKED");

        mockMvc.perform(get("/api/v1/events/unchecked-count"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.count").value(2));
    }

    private String body(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private long insertEvent(String status) {
        return insertEvent(status, "CONGESTION", 2);
    }

    // JSON 칼럼에는 FORMAT JSON으로 넣어야 객체로 저장된다(EventDetailTest와 같음)
    private long insertEvent(String status, String type, Integer peakLevel) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, started_at, peak_level, peak_value, peak_count,
                                   threshold_snapshot, status)
                VALUES (?, ?, ?, ?, 0.8, 10, ? FORMAT JSON, ?)
                """,
                type,
                zoneId,
                T0,
                peakLevel,
                CONGESTION_SNAPSHOT,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM event", Long.class);
    }

    /** 보낸 내용을 모아 두는 가짜 연결. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> sent = new CopyOnWriteArrayList<>();

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            builder.build().forEach(part -> sent.add(String.valueOf(part.getData())));
        }

        String text() {
            return String.join("", sent);
        }
    }
}
