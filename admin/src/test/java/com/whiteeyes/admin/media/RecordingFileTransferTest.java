package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * 녹화 파일 전송(FNC-A-044). 검수 기준: 파일 주소를 직접 입력하면 404 —
 * 앱은 /protected/media/를 직접 내주지 않고(404), 권한·존재·보관 기간을 확인한 뒤 X-Accel-Redirect만 돌려준다.
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecordingFileTransferTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String VIDEO = "dummy/videos/a.mp4";
    private static final String BOX = "recordings/live/cam7/2026-10-12 14.json.gz";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final LocalDateTime now = LocalDateTime.now(KST).withNano(0);
    private long cameraId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999992', '파일 전송 테스트동', '{}', 37.600000, 127.002000)");
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999992'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, '파일 전송 1구역', 100.00, '{}', 37.600000, 127.002000)",
                dongId);
        long zoneId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', 'STOPPED')",
                zoneId);
        cameraId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    // recording·camera·zone·region을 남기면 뒤에 도는 다른 테스트가 지우다 FK로 실패한다
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM recording");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("DELETE FROM region WHERE code = '99999992'");
    }

    @Test
    @DisplayName("FNC-A-044 영상은 본문 없이 X-Accel-Redirect: /protected/media/{video_path}만 돌려준다")
    void video_returnsAccelRedirect() throws Exception {
        long id = insertRecording(now.minusHours(1), VIDEO, "dummy/boxes/a.json.gz");

        var result = mockMvc.perform(get("/api/v1/recordings/" + id + "/video"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/protected/media/dummy/videos/a.mp4"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-044 좌표 파일은 X-Accel-Redirect: /protected/media/{box_path}를 돌려주고 경로의 공백·한글은 인코딩한다")
    void boxes_returnsAccelRedirect_encodedPath() throws Exception {
        long id = insertRecording(now.minusHours(1), VIDEO, BOX);

        mockMvc.perform(get("/api/v1/recordings/" + id + "/boxes"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/protected/media/recordings/live/cam7/2026-10-12%2014.json.gz"));
    }

    @Test
    @DisplayName("FNC-A-044 Range 요청이어도 앱은 같은 헤더만 돌려주고 구간 처리는 nginx가 한다")
    void video_rangeRequest_sameRedirect() throws Exception {
        long id = insertRecording(now.minusHours(1), VIDEO, "dummy/boxes/a.json.gz");

        mockMvc.perform(get("/api/v1/recordings/" + id + "/video").header("Range", "bytes=0-1023"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/protected/media/dummy/videos/a.mp4"));
    }

    @Test
    @DisplayName("FNC-A-044 없는 녹화는 영상·좌표 모두 404")
    void missingRecording_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/recordings/999999/video"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/recordings/999999/boxes")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-044 보관 기간(30일)을 지난 녹화는 행이 남아 있어도 404 RECORDING_EXPIRED이고 헤더가 없다")
    void expiredRecording_notFound() throws Exception {
        long id = insertRecording(now.minusDays(31), VIDEO, "dummy/boxes/a.json.gz");

        mockMvc.perform(get("/api/v1/recordings/" + id + "/video"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_EXPIRED"))
                .andExpect(header().doesNotExist("X-Accel-Redirect"));
        mockMvc.perform(get("/api/v1/recordings/" + id + "/boxes")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-044 파일 주소(/protected/media/…)를 직접 요청하면 앱은 파일을 내주지 않고 404")
    void directFileUrl_notFound() throws Exception {
        mockMvc.perform(get("/protected/media/dummy/videos/a.mp4")).andExpect(status().isNotFound());
        mockMvc.perform(get("/dummy/videos/a.mp4")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-044 로그인하지 않으면 401이고 헤더를 주지 않는다")
    void notLoggedIn_unauthorized() throws Exception {
        long id = insertRecording(now.minusHours(1), VIDEO, "dummy/boxes/a.json.gz");

        mockMvc.perform(get("/api/v1/recordings/" + id + "/video").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Accel-Redirect"));
    }

    private long insertRecording(LocalDateTime startedAt, String videoPath, String boxPath) {
        jdbcTemplate.update(
                "INSERT INTO recording (camera_id, started_at, ended_at, video_path, box_path) VALUES (?, ?, ?, ?, ?)",
                cameraId,
                Timestamp.valueOf(startedAt),
                Timestamp.valueOf(startedAt.plusMinutes(1)),
                videoPath,
                boxPath);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM recording", Long.class);
    }
}
