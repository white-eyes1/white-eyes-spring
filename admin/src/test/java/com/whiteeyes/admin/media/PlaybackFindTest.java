package com.whiteeyes.admin.media;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

/**
 * 다시보기 녹화 찾기(FNC-A-043). 검수 기준: 라이브 최근 1~2분은 '영상 없음'.
 * 시각은 지금(KST)을 기준으로 만든다(30일 보관 기간 판정이 지금을 쓰므로).
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlaybackFindTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final String VIDEO = "dummy/videos/a.mp4";
    private static final String BOX = "dummy/boxes/a.json.gz";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final LocalDateTime now = LocalDateTime.now(KST).withNano(0);
    private long dongId;
    private long zoneId;
    private long otherZoneId;
    private long cameraId;
    private long otherCameraId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999991', '다시보기 테스트동', '{}', 37.600000, 127.002000)");
        dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999991'", Long.class);
        zoneId = insertZone("다시보기 1구역");
        otherZoneId = insertZone("다시보기 2구역");
        cameraId = insertCamera(zoneId, false);
        otherCameraId = insertCamera(otherZoneId, false);
    }

    // recording·camera·zone·region을 남기면 뒤에 도는 다른 테스트가 지우다 FK로 실패한다
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM recording");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("DELETE FROM region WHERE code = '99999991'");
    }

    @Test
    @DisplayName("FNC-A-043 at을 포함하는 녹화를 찾고 재생 시작 위치(seekSec)와 주소를 돌려준다. 파일 경로는 노출하지 않는다")
    void findAt_returnsRecordingAndSeekSec() throws Exception {
        LocalDateTime start = now.minusHours(2);
        long recordingId = insertRecording(cameraId, start, start.plusMinutes(1));

        playback(zoneId, start.plusSeconds(37).plusNanos(500_000_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.videoUrl").value("/api/v1/recordings/" + recordingId + "/video"))
                .andExpect(jsonPath("$.boxesUrl").value("/api/v1/recordings/" + recordingId + "/boxes"))
                .andExpect(jsonPath("$.seekSec").value(37.5))
                .andExpect(jsonPath("$.startedAt").value(start.atOffset(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.videoPath").doesNotExist())
                .andExpect(jsonPath("$.boxPath").doesNotExist());
    }

    @Test
    @DisplayName("FNC-A-043 응답 주소 앞에는 컨텍스트 경로(/admin)가 붙는다")
    void findAt_urlsIncludeContextPath() throws Exception {
        LocalDateTime start = now.minusHours(2);
        long recordingId = insertRecording(cameraId, start, start.plusMinutes(1));

        mockMvc.perform(get("/admin/api/v1/playback")
                        .contextPath("/admin")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("at", offset(start.plusSeconds(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value("/admin/api/v1/recordings/" + recordingId + "/video"))
                .andExpect(jsonPath("$.boxesUrl").value("/admin/api/v1/recordings/" + recordingId + "/boxes"));
    }

    @Test
    @DisplayName("FNC-A-043 시작 시각과 같으면 포함(seekSec 0), 종료 시각과 같으면 포함하지 않는다")
    void findAt_boundaries() throws Exception {
        LocalDateTime start = now.minusHours(2);
        insertRecording(cameraId, start, start.plusMinutes(1));

        playback(zoneId, start).andExpect(status().isOk()).andExpect(jsonPath("$.seekSec").value(0.0));
        playback(zoneId, start.plusMinutes(1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-043 여러 녹화가 겹치면 started_at이 가장 늦은 것을 고른다")
    void findAt_overlapping_picksLatestStarted() throws Exception {
        LocalDateTime start = now.minusHours(2);
        insertRecording(cameraId, start, start.plusMinutes(10));
        long later = insertRecording(cameraId, start.plusMinutes(2), start.plusMinutes(10));

        playback(zoneId, start.plusMinutes(3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(later))
                .andExpect(jsonPath("$.seekSec").value(60.0));
    }

    @Test
    @DisplayName("FNC-A-043 ended_at이 비어 있는(진행 중) 녹화도 at이 시작 이후면 포함한다")
    void findAt_openRecording_included() throws Exception {
        LocalDateTime start = now.minusHours(2);
        long open = insertRecording(cameraId, start, null);

        playback(zoneId, start.plusMinutes(5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(open))
                .andExpect(jsonPath("$.seekSec").value(300.0))
                .andExpect(jsonPath("$.endedAt").value((Object) null));
    }

    @Test
    @DisplayName("FNC-A-043 삭제된 캠의 녹화도 찾는다")
    void findAt_deletedCamera_included() throws Exception {
        long deletedCamera = insertCamera(zoneId, true);
        LocalDateTime start = now.minusHours(3);
        long recordingId = insertRecording(deletedCamera, start, start.plusMinutes(1));

        playback(zoneId, start.plusSeconds(10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId));
    }

    @Test
    @DisplayName("FNC-A-043 다른 구역 캠의 녹화는 찾지 않는다")
    void findAt_otherZone_notFound() throws Exception {
        LocalDateTime start = now.minusHours(2);
        insertRecording(otherCameraId, start, start.plusMinutes(1));

        playback(zoneId, start.plusSeconds(10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("영상 없음"));
    }

    @Test
    @DisplayName("FNC-A-043 라이브 최근 1~2분은 아직 등록된 녹화가 없어 '영상 없음'이다")
    void findAt_recentMinutes_notFound() throws Exception {
        // 마지막으로 등록된 녹화가 3분 전에 끝났다면, 1분 전 시각은 아직 녹화 행이 없다
        insertRecording(cameraId, now.minusMinutes(4), now.minusMinutes(3));

        playback(zoneId, now.minusMinutes(1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-043 캠이 없는 구역은 404 RECORDING_NOT_FOUND")
    void findAt_zoneWithoutCamera_notFound() throws Exception {
        long emptyZone = insertZone("캠 없는 구역");

        playback(emptyZone, now.minusHours(1)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-043 at이 30일보다 앞서면 녹화 행이 남아 있어도 404 RECORDING_EXPIRED('보관 기간 지남')")
    void findAt_olderThan30Days_expired() throws Exception {
        LocalDateTime start = now.minusDays(31);
        insertRecording(cameraId, start, start.plusMinutes(1));

        playback(zoneId, start.plusSeconds(10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_EXPIRED"))
                .andExpect(jsonPath("$.message").value("보관 기간 지남"));
    }

    @Test
    @DisplayName("FNC-A-043 29일 전 시각은 보관 기간 안이라 찾는다")
    void findAt_within30Days_found() throws Exception {
        LocalDateTime start = now.minusDays(29);
        insertRecording(cameraId, start, start.plusMinutes(1));

        playback(zoneId, start.plusSeconds(10)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FNC-A-043 latest=true면 구역에서 가장 늦게 시작한 녹화를 seekSec 0으로 돌려준다")
    void findLatest_returnsLatestStarted() throws Exception {
        insertRecording(cameraId, now.minusHours(5), now.minusHours(5).plusMinutes(1));
        long latest = insertRecording(cameraId, now.minusHours(1), now.minusHours(1).plusMinutes(1));
        insertRecording(otherCameraId, now.minusMinutes(10), now.minusMinutes(9));

        mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zoneId)).param("latest", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(latest))
                .andExpect(jsonPath("$.seekSec").value(0.0));
    }

    @Test
    @DisplayName("FNC-A-043 latest=true는 30일 안의 녹화만 보고, 없으면 404 RECORDING_NOT_FOUND")
    void findLatest_onlyWithin30Days() throws Exception {
        insertRecording(cameraId, now.minusDays(31), now.minusDays(31).plusMinutes(1));

        mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zoneId)).param("latest", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    @DisplayName("FNC-A-043 at 형식: UTC 오프셋은 한국 시간으로 바꾸고, 오프셋이 없으면 한국 시간으로 본다. 주소의 '+'가 공백이 된 값도 받는다")
    void findAt_acceptsOffsetVariants() throws Exception {
        LocalDateTime start = now.minusHours(2).withNano(0);
        long recordingId = insertRecording(cameraId, start, start.plusMinutes(1));
        LocalDateTime target = start.plusSeconds(30);

        String utc = target.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime().toString();
        mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zoneId)).param("at", utc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(recordingId))
                .andExpect(jsonPath("$.seekSec").value(30.0));
        mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zoneId)).param("at", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seekSec").value(30.0));
        mockMvc.perform(get("/api/v1/playback")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("at", target + " 09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seekSec").value(30.0));
    }

    @Test
    @DisplayName("FNC-A-043 zoneId가 없거나 at·latest 둘 다 없거나 at 형식이 틀리면 400")
    void invalidInput_badRequest() throws Exception {
        mockMvc.perform(get("/api/v1/playback").param("at", offset(now.minusHours(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zoneId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zoneId)).param("at", "어제"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private ResultActions playback(long zone, LocalDateTime at) throws Exception {
        return mockMvc.perform(get("/api/v1/playback").param("zoneId", String.valueOf(zone)).param("at", offset(at)));
    }

    private String offset(LocalDateTime at) {
        return at.atZone(KST).format(WITH_OFFSET);
    }

    private long insertZone(String name) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000)",
                dongId,
                name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private long insertCamera(long zone, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status, deleted_at)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', 'STOPPED', ?)",
                zone,
                deleted ? Timestamp.valueOf(now.minusDays(1)) : null);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private long insertRecording(long camera, LocalDateTime startedAt, LocalDateTime endedAt) {
        jdbcTemplate.update(
                "INSERT INTO recording (camera_id, started_at, ended_at, video_path, box_path) VALUES (?, ?, ?, ?, ?)",
                camera,
                Timestamp.valueOf(startedAt),
                endedAt == null ? null : Timestamp.valueOf(endedAt),
                VIDEO,
                BOX);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM recording", Long.class);
    }
}
