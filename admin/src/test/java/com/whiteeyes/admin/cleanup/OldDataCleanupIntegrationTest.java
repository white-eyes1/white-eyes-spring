package com.whiteeyes.admin.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.cleanup.service.OldDataCleanupService;

// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 시각은 스케줄러가 건드리지 않을 먼 미래(2099년)로 둔다
/** 오래된 데이터 정리(FNC-A-032) */
@SpringBootTest
@Transactional
class OldDataCleanupIntegrationTest {

    // 1분 통계 기준은 오늘 0시 − 90일 = 2098-10-05 00:00, 녹화 기준은 지금 − 30일 = 2098-12-04 12:30
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 3, 12, 30);
    private static final LocalDateTime STAT_CUTOFF = LocalDateTime.of(2098, 10, 5, 0, 0);
    private static final LocalDateTime RECORDING_CUTOFF = LocalDateTime.of(2098, 12, 4, 12, 30);

    @Autowired
    private OldDataCleanupService cleanupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("FNC-A-032 1분 통계는 오늘 0시 − 90일보다 앞선 것만 지움(경계 시각은 남김)")
    void deletesStatMinuteOlderThanCutoff() {
        long zoneId = insertZone("99999994");
        insertStat(zoneId, STAT_CUTOFF.minusMinutes(1));
        insertStat(zoneId, STAT_CUTOFF);
        insertStat(zoneId, STAT_CUTOFF.plusMinutes(1));

        cleanupService.cleanUp(NOW);

        assertThat(statTimes(zoneId)).containsExactly(STAT_CUTOFF, STAT_CUTOFF.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-032 녹화 기록은 현재 − 30일보다 먼저 시작한 것만 지움(경계 시각은 남김)")
    void deletesRecordingOlderThanCutoff() {
        long cameraId = insertCamera(insertZone("99999994"));
        insertRecording(cameraId, RECORDING_CUTOFF.minusSeconds(1));
        insertRecording(cameraId, RECORDING_CUTOFF);
        insertRecording(cameraId, RECORDING_CUTOFF.plusSeconds(1));

        cleanupService.cleanUp(NOW);

        assertThat(recordingTimes(cameraId)).containsExactly(RECORDING_CUTOFF, RECORDING_CUTOFF.plusSeconds(1));
    }

    @Test
    @DisplayName("FNC-A-032 진행 중(ended_at 없음)인 오래된 녹화 기록도 started_at 기준으로 지움")
    void deletesOpenRecordingByStartedAt() {
        long cameraId = insertCamera(insertZone("99999994"));
        insertRecording(cameraId, RECORDING_CUTOFF.minusDays(1), null);

        cleanupService.cleanUp(NOW);

        assertThat(recordingTimes(cameraId)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-032 1만 건을 넘는 1분 통계도 여러 번에 나눠 모두 지움")
    void deletesMoreThanOneBatch() {
        long zoneId = insertZone("99999994");
        int count = OldDataCleanupService.BATCH_SIZE + 1;
        LocalDateTime start = STAT_CUTOFF.minusDays(30);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {zoneId, Timestamp.valueOf(start.plusMinutes(i))});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density)"
                        + " VALUES (?, ?, 1.00, 1, 1, 0.1000)",
                rows);
        insertStat(zoneId, STAT_CUTOFF);

        cleanupService.cleanUp(NOW);

        assertThat(statTimes(zoneId)).containsExactly(STAT_CUTOFF);
    }

    @Test
    @DisplayName("FNC-A-032 다시 실행해도 결과 동일, 지울 것이 없어도 오류 없음")
    void isIdempotent() {
        long zoneId = insertZone("99999994");
        insertStat(zoneId, STAT_CUTOFF.minusDays(1));
        insertStat(zoneId, STAT_CUTOFF);

        cleanupService.cleanUp(NOW);
        cleanupService.cleanUp(NOW);

        assertThat(statTimes(zoneId)).containsExactly(STAT_CUTOFF);
    }

    private List<LocalDateTime> statTimes(long zoneId) {
        return jdbcTemplate
                .queryForList("SELECT stat_at FROM zone_stat_minute WHERE zone_id = ? ORDER BY stat_at", Timestamp.class, zoneId)
                .stream()
                .map(Timestamp::toLocalDateTime)
                .toList();
    }

    private List<LocalDateTime> recordingTimes(long cameraId) {
        return jdbcTemplate
                .queryForList("SELECT started_at FROM recording WHERE camera_id = ? ORDER BY started_at", Timestamp.class, cameraId)
                .stream()
                .map(Timestamp::toLocalDateTime)
                .toList();
    }

    private void insertStat(long zoneId, LocalDateTime statAt) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density)"
                        + " VALUES (?, ?, 1.00, 1, 1, 0.1000)",
                zoneId,
                Timestamp.valueOf(statAt));
    }

    private void insertRecording(long cameraId, LocalDateTime startedAt) {
        insertRecording(cameraId, startedAt, startedAt.plusMinutes(1));
    }

    private void insertRecording(long cameraId, LocalDateTime startedAt, LocalDateTime endedAt) {
        jdbcTemplate.update(
                "INSERT INTO recording (camera_id, started_at, ended_at, video_path, box_path) VALUES (?, ?, ?, ?, ?)",
                cameraId,
                Timestamp.valueOf(startedAt),
                endedAt == null ? null : Timestamp.valueOf(endedAt),
                "live/a.mp4",
                "live/a.json.gz");
    }

    // zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 테스트가 끝나면 롤백된다
    private long insertZone(String regionCode) {
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '" + regionCode + "', '정리 테스트동', '{}', 37.600000, 127.002000)");
        Long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '" + regionCode + "'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, '정리 구역', ?, '{}', 37.600000, 127.002000)",
                dongId,
                new BigDecimal("100.00"));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private long insertCamera(long zoneId) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, status)"
                        + " VALUES (?, '캠', 'DUMMY', 'dummy/videos/a.mp4', 'STOPPED')",
                zoneId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }
}
