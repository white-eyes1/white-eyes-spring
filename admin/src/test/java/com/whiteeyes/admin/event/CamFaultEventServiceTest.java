package com.whiteeyes.admin.event;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.admin.notification.service.NotificationPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.event.service.CamFaultEventService;
import com.whiteeyes.admin.stat.service.LastResultTimeStore;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;

import jakarta.persistence.EntityManager;

/**
 * 캠 이상 감지(FNC-A-035). 기준은 기본값(결과 없음 허용 60초)이다.
 * 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에 테스트 시각은 먼 미래(2099년)로 두고, 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class CamFaultEventServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 10, 0, 0);
    private static final int TIMEOUT_SEC = 60;

    @MockitoSpyBean
    private NotificationPublisher notificationPublisher;

    @Autowired
    private CamFaultEventService camFaultEventService;

    @Autowired
    private LastResultTimeStore lastResultTimeStore;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("FNC-A-035 ERROR 캠이면 캠 이상 이벤트가 열리고 camera_id·zone_id·기준값 스냅샷이 채워진다")
    void opensEventForErrorCamera() {
        Long zoneId = saveZone("캠이상 오류 구역", "1");
        long cameraId = insertCamera(zoneId, "ERROR", true, false);

        camFaultEventService.detect(NOW);

        List<Event> events = openFaults(cameraId);
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.getType()).isEqualTo(EventType.CAM_FAULT);
        assertThat(event.getCameraId()).isEqualTo(cameraId);
        assertThat(event.getZoneId()).isEqualTo(zoneId);
        assertThat(event.getStartedAt()).isEqualTo(NOW);
        assertThat(event.getStatus()).isEqualTo(EventStatus.UNCHECKED);
        assertThat(event.getPeakLevel()).isNull();
        assertThat(event.getPeakValue()).isNull();
        assertThat(event.getPeakCount()).isNull();
        assertThat(event.getThresholdSnapshot()).contains("\"timeoutSec\"").contains(String.valueOf(TIMEOUT_SEC));
    }

    @Test
    @DisplayName("FNC-A-035 새 캠 이상 이벤트를 열면 관리자 화면 알림을 보내고, 이미 열려 있으면 다시 보내지 않는다")
    void notifiesOnlyWhenNewFaultEventIsOpened() {
        Long zoneId = saveZone("캠이상 알림 구역", "1");
        long cameraId = insertCamera(zoneId, "ERROR", true, false);

        camFaultEventService.detect(NOW);
        camFaultEventService.detect(NOW.plusSeconds(10));

        Event event = openFaults(cameraId).get(0);
        verify(notificationPublisher, times(1))
                .eventCreated(eq(event.getId()), eq(EventType.CAM_FAULT), eq(zoneId), isNull(), eq(NOW));
        verify(notificationPublisher, times(1)).uncheckedCountChanged();
    }

    @Test
    @DisplayName("FNC-A-035 RUNNING 캠은 마지막 결과가 59초 전이면 열지 않고 60초 전이면 연다")
    void opensEventForRunningCameraOnlyAtTimeout() {
        Long zoneId = saveZone("캠이상 지연 구역", "1");
        long cameraId = insertCamera(zoneId, "RUNNING", true, false);

        lastResultTimeStore.update(cameraId, NOW.minusSeconds(TIMEOUT_SEC - 1));
        camFaultEventService.detect(NOW);
        assertThat(openFaults(cameraId)).isEmpty();

        camFaultEventService.detect(NOW.plusSeconds(1));
        assertThat(openFaults(cameraId)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-035 이미 열린 캠 이상 이벤트가 있으면 다시 만들지 않는다")
    void doesNotDuplicateOpenEvent() {
        Long zoneId = saveZone("캠이상 중복 구역", "1");
        long cameraId = insertCamera(zoneId, "ERROR", true, false);

        camFaultEventService.detect(NOW);
        camFaultEventService.detect(NOW.plusSeconds(10));
        camFaultEventService.openOnError(cameraId, NOW.plusSeconds(20));

        assertThat(allFaults(cameraId)).hasSize(1);
        assertThat(openFaults(cameraId)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-035 PREPARING·STOPPED 캠과 run_requested=0·삭제된 캠은 검사하지 않는다")
    void skipsCamerasOutOfScope() {
        long preparing = insertCamera(saveZone("캠이상 준비 구역", "1"), "PREPARING", true, false);
        long stopped = insertCamera(saveZone("캠이상 정지 구역", "1"), "STOPPED", false, false);
        long notRequested = insertCamera(saveZone("캠이상 비요청 구역", "1"), "ERROR", false, false);
        long deleted = insertCamera(saveZone("캠이상 삭제 구역", "1"), "ERROR", true, true);
        long stoppedRequested = insertCamera(saveZone("캠이상 정지요청 구역", "1"), "STOPPED", true, false);

        camFaultEventService.detect(NOW);
        camFaultEventService.detect(NOW.plusSeconds(120));

        for (long cameraId : new long[] {preparing, stopped, notRequested, deleted, stoppedRequested}) {
            assertThat(allFaults(cameraId)).as("cameraId=%d", cameraId).isEmpty();
        }
    }

    @Test
    @DisplayName("FNC-A-035 결과가 다시 들어오면 다음 검사에서 열린 이벤트가 닫힌다")
    void closesWhenResultArrivesAgain() {
        Long zoneId = saveZone("캠이상 복구 구역", "1");
        long cameraId = insertCamera(zoneId, "RUNNING", true, false);
        lastResultTimeStore.update(cameraId, NOW.minusSeconds(TIMEOUT_SEC));
        camFaultEventService.detect(NOW);
        assertThat(openFaults(cameraId)).hasSize(1);

        LocalDateTime later = NOW.plusSeconds(10);
        lastResultTimeStore.update(cameraId, later);
        camFaultEventService.detect(later);

        assertThat(openFaults(cameraId)).isEmpty();
        assertThat(allFaults(cameraId).get(0).getEndedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("FNC-A-035 마지막 결과 시각이 없는 캠은 시작 시각으로 채워 바로 열지 않는다")
    void seedsMissingLastResultTime() {
        Long zoneId = saveZone("캠이상 초기화 구역", "1");
        long cameraId = insertCamera(zoneId, "RUNNING", true, false);
        assertThat(lastResultTimeStore.findLastResultAt(cameraId)).isEmpty();

        camFaultEventService.detect(NOW);

        assertThat(lastResultTimeStore.findLastResultAt(cameraId)).contains(NOW);
        assertThat(openFaults(cameraId)).isEmpty();
        camFaultEventService.detect(NOW.plusSeconds(TIMEOUT_SEC - 1));
        assertThat(openFaults(cameraId)).isEmpty();
        camFaultEventService.detect(NOW.plusSeconds(TIMEOUT_SEC));
        assertThat(openFaults(cameraId)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-035 재시작 직후 결과를 아직 한 번도 받지 못한 RUNNING 캠의 열린 이벤트는 닫지 않는다")
    void keepsOpenEventUntilFirstResultAfterRestart() {
        Long zoneId = saveZone("캠이상 재시작 구역", "1");
        long cameraId = insertCamera(zoneId, "RUNNING", true, false);
        camFaultEventService.openOnError(cameraId, NOW.minusSeconds(300));
        assertThat(openFaults(cameraId)).hasSize(1);
        assertThat(lastResultTimeStore.findLastResultAt(cameraId)).isEmpty();

        camFaultEventService.detect(NOW);
        assertThat(openFaults(cameraId)).as("재시작 직후 검사").hasSize(1);

        // 결과를 아직 한 번도 받지 못했으면 다음 검사들(10초 간격)에서도 정상으로 보지 않고 이벤트를 둔다
        for (int sec = 10; sec < TIMEOUT_SEC; sec += 10) {
            camFaultEventService.detect(NOW.plusSeconds(sec));
            assertThat(openFaults(cameraId)).as("재시작 %d초 뒤 검사", sec).hasSize(1);
        }

        // 분석 서버가 계속 죽어 있으면 시작 시각 기준 60초 뒤에도 같은 이벤트가 이어진다(둘로 쪼개지지 않음)
        camFaultEventService.detect(NOW.plusSeconds(TIMEOUT_SEC));
        assertThat(allFaults(cameraId)).hasSize(1);
        assertThat(openFaults(cameraId)).hasSize(1);

        // 결과가 다시 들어오면 다음 검사에서 닫힌다
        LocalDateTime later = NOW.plusSeconds(TIMEOUT_SEC + 10);
        lastResultTimeStore.update(cameraId, later);
        camFaultEventService.detect(later);
        assertThat(openFaults(cameraId)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-035 재시작 직후라도 결과가 이미 들어온 RUNNING 캠의 열린 이벤트는 검사에서 닫는다")
    void closesOpenEventWhenResultAlreadyReceivedAfterRestart() {
        Long zoneId = saveZone("캠이상 재시작복구 구역", "1");
        long cameraId = insertCamera(zoneId, "RUNNING", true, false);
        camFaultEventService.openOnError(cameraId, NOW.minusSeconds(300));
        lastResultTimeStore.update(cameraId, NOW.minusSeconds(1));

        camFaultEventService.detect(NOW);

        assertThat(openFaults(cameraId)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-035 RUNNING 보고를 받으면 열린 이벤트가 닫히고 마지막 결과 시각이 보고 시각으로 올라간다")
    void closesAndRaisesBaselineOnRecovered() {
        Long zoneId = saveZone("캠이상 보고복구 구역", "1");
        long cameraId = insertCamera(zoneId, "ERROR", true, false);
        camFaultEventService.detect(NOW);
        assertThat(openFaults(cameraId)).hasSize(1);

        LocalDateTime recoveredAt = NOW.plusSeconds(30);
        camFaultEventService.closeOnRecovered(cameraId, recoveredAt, true);

        assertThat(openFaults(cameraId)).isEmpty();
        assertThat(allFaults(cameraId).get(0).getEndedAt()).isEqualTo(recoveredAt);
        assertThat(lastResultTimeStore.findLastResultAt(cameraId)).contains(recoveredAt);
    }

    @Test
    @DisplayName("FNC-A-035 이미 RUNNING인 캠의 반복 보고는 마지막 결과 시각을 올리지 않는다")
    void doesNotRaiseBaselineOnRepeatedRunningReport() {
        long cameraId = insertCamera(saveZone("캠이상 반복보고 구역", "1"), "RUNNING", true, false);
        lastResultTimeStore.update(cameraId, NOW.minusSeconds(50));

        camFaultEventService.closeOnRecovered(cameraId, NOW, false);

        assertThat(lastResultTimeStore.findLastResultAt(cameraId)).contains(NOW.minusSeconds(50));
    }

    @Test
    @DisplayName("FNC-A-035 반복 RUNNING 보고로는 열린 이벤트를 닫지 않고, 결과가 들어온 뒤 점검에서 닫혀 이벤트가 1건이다")
    void keepsOpenEventOnRepeatedRunningReportUntilResultArrives() {
        long cameraId = insertCamera(saveZone("캠이상 반복유지 구역", "1"), "RUNNING", true, false);
        lastResultTimeStore.update(cameraId, NOW.minusSeconds(TIMEOUT_SEC));
        camFaultEventService.detect(NOW);
        assertThat(openFaults(cameraId)).hasSize(1);

        // 분석 서버를 다시 켜면 상태가 이미 RUNNING이라 반복 보고만 온다
        camFaultEventService.closeOnRecovered(cameraId, NOW.plusSeconds(5), false);
        camFaultEventService.detect(NOW.plusSeconds(6));
        assertThat(openFaults(cameraId)).hasSize(1);
        assertThat(allFaults(cameraId)).hasSize(1);

        LocalDateTime resultAt = NOW.plusSeconds(10);
        lastResultTimeStore.update(cameraId, resultAt);
        camFaultEventService.detect(resultAt);
        camFaultEventService.detect(resultAt.plusSeconds(1));

        assertThat(openFaults(cameraId)).isEmpty();
        assertThat(allFaults(cameraId)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-035 캠이 정지되면 열린 이벤트가 닫히고, 정지·삭제로 감시 대상에서 빠진 캠의 이벤트도 다음 검사에서 닫힌다")
    void closesOnStopAndWhenLeavingWatch() {
        long stopCamera = insertCamera(saveZone("캠이상 정지종료 구역", "1"), "ERROR", true, false);
        long leaveCamera = insertCamera(saveZone("캠이상 이탈종료 구역", "1"), "ERROR", true, false);
        camFaultEventService.detect(NOW);
        assertThat(openFaults(stopCamera)).hasSize(1);
        assertThat(openFaults(leaveCamera)).hasSize(1);

        // 실제 정지는 run_requested=0과 STOPPED로 함께 바뀌므로 정지 캠도 같은 상태로 만든다
        camFaultEventService.closeOnStop(stopCamera, NOW.plusSeconds(5));
        jdbcTemplate.update("UPDATE camera SET run_requested = 0, status = 'STOPPED' WHERE id = ?", stopCamera);
        jdbcTemplate.update("UPDATE camera SET run_requested = 0 WHERE id = ?", leaveCamera);
        // JDBC로 바꾼 값을 JPA가 다시 읽도록 이미 읽어 둔 엔티티를 비운다
        entityManager.flush();
        entityManager.clear();
        camFaultEventService.detect(NOW.plusSeconds(10));

        assertThat(openFaults(stopCamera)).isEmpty();
        assertThat(allFaults(stopCamera).get(0).getEndedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(openFaults(leaveCamera)).isEmpty();
        assertThat(allFaults(leaveCamera).get(0).getEndedAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    @DisplayName("FNC-A-035 run_requested=0인 캠은 ERROR를 보고받아도 이벤트를 열지 않는다")
    void openOnErrorIgnoresNotRequestedCamera() {
        Long zoneId = saveZone("캠이상 보고무시 구역", "1");
        long cameraId = insertCamera(zoneId, "ERROR", false, false);

        camFaultEventService.openOnError(cameraId, NOW);

        assertThat(allFaults(cameraId)).isEmpty();
    }

    private List<Event> openFaults(long cameraId) {
        return allFaults(cameraId).stream().filter(e -> e.getEndedAt() == null).toList();
    }

    private List<Event> allFaults(long cameraId) {
        return eventRepository.findAll().stream()
                .filter(e -> e.getType() == EventType.CAM_FAULT && e.getCameraId() != null && e.getCameraId() == cameraId)
                .toList();
    }

    private long insertCamera(Long zoneId, String status, boolean runRequested, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO camera (zone_id, name, source_type, source_uri, run_requested, status, progress, deleted_at) "
                        + "VALUES (?, '캠이상 테스트 캠', 'DUMMY', 'dummy/videos/a.mp4', ?, ?, 0, ?)",
                zoneId,
                runRequested,
                status,
                deleted ? NOW.minusDays(1) : null);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM camera", Long.class);
    }

    private Long saveZone(String name, String areaM2) {
        String code = String.valueOf(99980000 + Math.floorMod(name.hashCode(), 9999));
        jdbcTemplate.update(
                "INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                        + " VALUES ('EMD', ?, ?, '{}', 37.600000, 127.002000)",
                code,
                name + " 동");
        Long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
        Zone zone = new Zone(
                dongId, name, new BigDecimal(areaM2), "{}", new BigDecimal("37.600000"), new BigDecimal("127.002000"));
        return zoneRepository.save(zone).getId();
    }
}
