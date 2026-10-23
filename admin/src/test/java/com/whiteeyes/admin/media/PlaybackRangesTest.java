package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

/**
 * 다시보기 녹화 구간 조회(FNC-A-043 처리 순서 6~8, GET /api/v1/playback/ranges).
 * 시각은 지금(KST)을 기준으로 만든다(30일 보관 기간 판정과 진행 중 녹화의 끝이 지금을 쓰므로).
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlaybackRangesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final String VIDEO = "dummy/videos/a.mp4";
    private static final String BOX = "dummy/boxes/a.json.gz";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private final LocalDateTime now = LocalDateTime.now(KST).withNano(0);
    private long dongId;
    private long zoneId;
    private long otherZoneId;
    private long cameraId;
    private long secondCameraId;
    private long otherCameraId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999992', '구간 테스트동', '{}', 37.600000, 127.002000)");
        dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999992'", Long.class);
        zoneId = insertZone("구간 1구역");
        otherZoneId = insertZone("구간 2구역");
        cameraId = insertCamera(zoneId, false);
        secondCameraId = insertCamera(zoneId, false);
        otherCameraId = insertCamera(otherZoneId, false);
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
    @DisplayName("FNC-A-043 범위 안 녹화 하나는 그 시작·끝 그대로 구간 하나로 돌려준다")
    void single_recording() throws Exception {
        LocalDateTime base = now.minusHours(3);
        insertRecording(cameraId, base.plusMinutes(10), base.plusMinutes(20));

        assertThat(ranges(zoneId, base, base.plusHours(1)))
                .containsExactly(range(base.plusMinutes(10), base.plusMinutes(20)));
    }

    @Test
    @DisplayName("FNC-A-043 범위를 벗어난 부분은 자르고, 범위 밖 녹화와 끝이 범위 시작과 같은 녹화는 넣지 않는다")
    void clipped_and_outside_excluded() throws Exception {
        LocalDateTime base = now.minusHours(3);
        insertRecording(cameraId, base.minusMinutes(5), base.plusMinutes(5));
        insertRecording(cameraId, base.plusMinutes(55), base.plusMinutes(70));
        insertRecording(cameraId, base.minusMinutes(30), base);
        insertRecording(cameraId, base.plusHours(1), base.plusHours(1).plusMinutes(5));

        assertThat(ranges(zoneId, base, base.plusHours(1)))
                .containsExactly(range(base, base.plusMinutes(5)), range(base.plusMinutes(55), base.plusHours(1)));
    }

    @Test
    @DisplayName("FNC-A-043 캠 구분 없이 합치고 겹치거나 2초 이하로 떨어진 구간은 하나로 잇는다. 3초 떨어지면 나눈다")
    void merges_overlap_and_small_gap() throws Exception {
        LocalDateTime base = now.minusHours(3);
        insertRecording(cameraId, base, base.plusMinutes(10));
        // 다른 캠의 겹치는 녹화
        insertRecording(secondCameraId, base.plusMinutes(8), base.plusMinutes(15));
        // 정확히 2초 떨어짐 → 이음
        insertRecording(cameraId, base.plusMinutes(15).plusSeconds(2), base.plusMinutes(20));
        // 3초 떨어짐 → 나눔
        insertRecording(cameraId, base.plusMinutes(20).plusSeconds(3), base.plusMinutes(25));

        assertThat(ranges(zoneId, base, base.plusHours(1)))
                .containsExactly(
                        range(base, base.plusMinutes(20)),
                        range(base.plusMinutes(20).plusSeconds(3), base.plusMinutes(25)));
    }

    @Test
    @DisplayName("FNC-A-043 다른 구역의 녹화는 넣지 않고, 삭제된 캠의 녹화는 넣는다")
    void zone_scoped_and_includes_deleted_camera() throws Exception {
        LocalDateTime base = now.minusHours(3);
        insertRecording(otherCameraId, base, base.plusMinutes(30));
        long deletedCamera = insertCamera(zoneId, true);
        insertRecording(deletedCamera, base.plusMinutes(40), base.plusMinutes(50));

        assertThat(ranges(zoneId, base, base.plusHours(1)))
                .containsExactly(range(base.plusMinutes(40), base.plusMinutes(50)));
    }

    @Test
    @DisplayName("FNC-A-043 진행 중 녹화(ended_at 없음)는 지금 시각까지로 본다")
    void open_recording_until_now() throws Exception {
        LocalDateTime start = now.minusMinutes(30);
        insertRecording(cameraId, start, null);

        List<LocalDateTime[]> result = ranges(zoneId, now.minusHours(1), now.plusHours(1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)[0]).isEqualTo(start);
        // 요청을 받은 시각까지(시험 시작 시각 이후, 범위 끝보다 앞)
        assertThat(result.get(0)[1]).isAfterOrEqualTo(now).isBefore(now.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-043 보관 30일을 넘은 부분은 넣지 않고, 전부 넘었으면 빈 목록이다")
    void retention_30_days() throws Exception {
        LocalDateTime cutoff = now.minusDays(30);
        insertRecording(cameraId, cutoff.minusHours(1), cutoff.plusHours(1));

        List<LocalDateTime[]> straddling = ranges(zoneId, cutoff.minusHours(2), cutoff.plusHours(3));
        assertThat(straddling).hasSize(1);
        assertThat(straddling.get(0)[0]).isAfterOrEqualTo(cutoff);
        assertThat(straddling.get(0)[1]).isEqualTo(cutoff.plusHours(1));

        jdbcTemplate.update("DELETE FROM recording");
        insertRecording(cameraId, now.minusDays(31), now.minusDays(31).plusHours(1));
        assertThat(ranges(zoneId, now.minusDays(31).minusMinutes(5), now.minusDays(31).plusHours(2))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-043 녹화가 없거나 캠이 없는 구역, 없는 구역은 404가 아니라 빈 목록이다")
    void empty_cases() throws Exception {
        long emptyZone = insertZone("캠 없는 구역");

        assertThat(ranges(zoneId, now.minusHours(2), now.minusHours(1))).isEmpty();
        assertThat(ranges(emptyZone, now.minusHours(2), now.minusHours(1))).isEmpty();
        assertThat(ranges(999_999L, now.minusHours(2), now.minusHours(1))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-043 범위가 정확히 24시간이면 받고, 24시간을 넘으면 400 INVALID_INPUT")
    void span_limit() throws Exception {
        LocalDateTime from = now.minusDays(2);
        request(zoneId, from, from.plusHours(24)).andExpect(status().isOk());
        request(zoneId, from, from.plusHours(24).plusSeconds(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-043 from ≥ to, 시각 형식 오류, from·to 누락은 400 INVALID_INPUT")
    void invalid_input() throws Exception {
        LocalDateTime base = now.minusHours(2);
        request(zoneId, base, base)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        request(zoneId, base.plusMinutes(1), base)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/v1/playback/ranges")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("from", "어제")
                        .param("to", offset(base)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/v1/playback/ranges")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("from", offset(base)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-043 응답의 시각은 한국 시간(+09:00)이고 파일 경로는 들어 있지 않다")
    void response_format() throws Exception {
        LocalDateTime base = now.minusHours(3);
        insertRecording(cameraId, base.plusMinutes(10), base.plusMinutes(20));

        String body = request(zoneId, base, base.plusHours(1))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("+09:00").doesNotContain("dummy/").doesNotContain("videoPath");
    }

    @Test
    @DisplayName("FNC-A-043 로그인하지 않으면 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/playback/ranges")
                        .with(anonymous())
                        .param("zoneId", String.valueOf(zoneId))
                        .param("from", offset(now.minusHours(2)))
                        .param("to", offset(now.minusHours(1))))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions request(long zone, LocalDateTime from, LocalDateTime to) throws Exception {
        return mockMvc.perform(get("/api/v1/playback/ranges")
                .param("zoneId", String.valueOf(zone))
                .param("from", offset(from))
                .param("to", offset(to)));
    }

    // 응답의 구간을 한국 시간 {from, to} 쌍으로
    private List<LocalDateTime[]> ranges(long zone, LocalDateTime from, LocalDateTime to) throws Exception {
        String body = request(zone, from, to).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode ranges = jsonMapper.readTree(body).get("ranges");
        List<LocalDateTime[]> result = new ArrayList<>();
        for (JsonNode item : ranges) {
            result.add(new LocalDateTime[] {
                OffsetDateTime.parse(item.get("from").asString()).atZoneSameInstant(KST).toLocalDateTime(),
                OffsetDateTime.parse(item.get("to").asString()).atZoneSameInstant(KST).toLocalDateTime()
            });
        }
        return result;
    }

    private static LocalDateTime[] range(LocalDateTime from, LocalDateTime to) {
        return new LocalDateTime[] {from, to};
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

    private void insertRecording(long camera, LocalDateTime startedAt, LocalDateTime endedAt) {
        jdbcTemplate.update(
                "INSERT INTO recording (camera_id, started_at, ended_at, video_path, box_path) VALUES (?, ?, ?, ?, ?)",
                camera,
                Timestamp.valueOf(startedAt),
                endedAt == null ? null : Timestamp.valueOf(endedAt),
                VIDEO,
                BOX);
    }
}
