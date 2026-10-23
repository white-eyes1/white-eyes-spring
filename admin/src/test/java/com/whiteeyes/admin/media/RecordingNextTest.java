package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import java.sql.Timestamp;
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

/** 이어 재생(FNC-A-045, GET /api/v1/recordings/{id}/next). 검수 기준: 1분 경계에서 자연스럽게 넘어감. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecordingNextTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String VIDEO = "dummy/videos/a.mp4";
    private static final String BOX = "dummy/boxes/a.json.gz";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final LocalDateTime base = LocalDateTime.now(KST).withNano(0).minusHours(3);
    private long cameraId;
    private long otherCameraId;

    @BeforeEach
    void prepare() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999994', '이어 재생 테스트동', '{}', 37.600000, 127.002000)");
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999994'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, '이어 재생 1구역', 100.00, '{}' FORMAT JSON, 37.600000, 127.002000)",
                dongId);
        long zoneId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
        cameraId = insertCamera(zoneId);
        otherCameraId = insertCamera(zoneId);
    }

    // recording·camera·zone·region을 남기면 뒤에 도는 다른 테스트가 지우다 FK로 실패한다
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM recording");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("DELETE FROM region WHERE code = '99999994'");
    }

    @Test
    @DisplayName("FNC-A-045 1분 경계에서 바로 이어지는 다음 녹화를 영상·좌표 주소와 seekSec 0으로 돌려준다")
    void next_adjacentRecording() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));
        long second = insertRecording(cameraId, base.plusMinutes(1), base.plusMinutes(2));

        next(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingId").value(second))
                .andExpect(jsonPath("$.videoUrl").value("/api/v1/recordings/" + second + "/video"))
                .andExpect(jsonPath("$.boxesUrl").value("/api/v1/recordings/" + second + "/boxes"))
                .andExpect(jsonPath("$.seekSec").value(0.0))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.endedAt").isString());
    }

    @Test
    @DisplayName("FNC-A-045 다음 녹화가 현재 끝보다 2초 일찍 시작해도(겹침) 이어지고, 3초 일찍 시작하면 이어지지 않는다")
    void next_overlapBoundary() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));
        long early = insertRecording(cameraId, base.plusMinutes(1).minusSeconds(2), base.plusMinutes(2));

        next(first).andExpect(status().isOk()).andExpect(jsonPath("$.recordingId").value(early));

        jdbcTemplate.update("DELETE FROM recording WHERE id = ?", early);
        insertRecording(cameraId, base.plusMinutes(1).minusSeconds(3), base.plusMinutes(2));
        next(first).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-045 두 녹화 사이가 10초까지 비면 이어 주고, 10초를 넘게 비면 404 '이후 영상 없음'이다")
    void next_gapBoundary() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));
        long tenSeconds = insertRecording(cameraId, base.plusMinutes(1).plusSeconds(10), base.plusMinutes(2));

        next(first).andExpect(status().isOk()).andExpect(jsonPath("$.recordingId").value(tenSeconds));

        jdbcTemplate.update("DELETE FROM recording WHERE id = ?", tenSeconds);
        insertRecording(cameraId, base.plusMinutes(1).plusSeconds(11), base.plusMinutes(2));
        next(first)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("이후 영상 없음"));
    }

    @Test
    @DisplayName("FNC-A-045 조건에 맞는 녹화가 여럿이면 가장 이르게 시작한 것을 고른다")
    void next_earliestStartedAt() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));
        insertRecording(cameraId, base.plusMinutes(1).plusSeconds(5), base.plusMinutes(3));
        long earliest = insertRecording(cameraId, base.plusMinutes(1), base.plusMinutes(2));

        next(first).andExpect(status().isOk()).andExpect(jsonPath("$.recordingId").value(earliest));
    }

    @Test
    @DisplayName("FNC-A-045 다른 캠의 녹화는 이어 주지 않는다")
    void next_sameCameraOnly() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));
        insertRecording(otherCameraId, base.plusMinutes(1), base.plusMinutes(2));

        next(first).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("이후 영상 없음"));
    }

    @Test
    @DisplayName("FNC-A-045 끝나지 않은(ended_at 없음) 녹화에는 이어질 녹화가 없다")
    void next_openRecording_notFound() throws Exception {
        long open = insertRecording(cameraId, base, null);
        insertRecording(cameraId, base.plusMinutes(1), base.plusMinutes(2));

        next(open).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("이후 영상 없음"));
    }

    @Test
    @DisplayName("FNC-A-045 없는 녹화 번호는 404 RECORDING_NOT_FOUND('영상 없음')")
    void next_unknownRecording() throws Exception {
        next(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("영상 없음"));
    }

    @Test
    @DisplayName("FNC-A-045 응답에 파일 경로를 넣지 않는다")
    void next_doesNotExposePaths() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));
        insertRecording(cameraId, base.plusMinutes(1), base.plusMinutes(2));

        String body = next(first).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("dummy/").doesNotContain("videoPath");
    }

    @Test
    @DisplayName("FNC-A-045 로그인하지 않으면 401")
    void next_notLoggedIn_unauthorized() throws Exception {
        long first = insertRecording(cameraId, base, base.plusMinutes(1));

        mockMvc.perform(get("/api/v1/recordings/" + first + "/next").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions next(long recordingId) throws Exception {
        return mockMvc.perform(get("/api/v1/recordings/" + recordingId + "/next"));
    }

    private long insertCamera(long zone) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', 'STOPPED')",
                zone);
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
