package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.admin.media.service.RecordingRegisterService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;

// 이전 녹화를 닫는 UPDATE 쿼리가 실제 DB에서 맞게 동작하는지 H2로 확인한다
/** 녹화 등록 수신(FNC-A-026) */
@SpringBootTest
@Transactional
class RecordingRegisterIntegrationTest {

    private static final String DUMMY_VIDEO = "dummy/videos/a.mp4";
    private static final String DUMMY_BOX = "dummy/boxes/a.json.gz";

    @Autowired
    private RecordingRegisterService recordingRegisterService;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private CameraRepository cameraRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long dongId;
    private Long dummyCameraId;

    // camera → zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 테스트가 끝나면 롤백된다
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999990', '녹화 테스트동', '{}', 37.600000, 127.002000)");
        dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999990'", Long.class);
        dummyCameraId = saveDummyCamera("녹화 1구역");
    }

    @Test
    @DisplayName("FNC-A-026 더미 반복 시 이전 녹화가 닫힘")
    void closesPreviousRecordingOnDummyRepeat() {
        Long firstId = register(dummyCameraId, "2026-10-12T14:00:00.000+09:00");
        Long secondId = register(dummyCameraId, "2026-10-12T14:05:00.250+09:00");

        Recording first = recordingRepository.findById(firstId).orElseThrow();
        Recording second = recordingRepository.findById(secondId).orElseThrow();
        assertThat(first.getEndedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 14, 5, 0, 250_000_000));
        assertThat(second.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("FNC-A-026 늦게 도착한 이전 반복 등록은 더 나중에 시작한 녹화를 닫지 않음")
    void lateRegistrationDoesNotCloseLaterRecording() {
        Long laterId = register(dummyCameraId, "2026-10-12T14:05:00+09:00");

        register(dummyCameraId, "2026-10-12T14:00:00+09:00");

        assertThat(recordingRepository.findById(laterId).orElseThrow().getEndedAt())
                .isNull();
    }

    @Test
    @DisplayName("FNC-A-026 다른 캠의 진행 중인 녹화는 닫지 않음")
    void doesNotCloseOtherCameraRecording() {
        Long otherCameraId = saveDummyCamera("녹화 2구역");
        Long otherId = register(otherCameraId, "2026-10-12T14:00:00+09:00");

        register(dummyCameraId, "2026-10-12T14:05:00+09:00");

        assertThat(recordingRepository.findById(otherId).orElseThrow().getEndedAt())
                .isNull();
    }

    private Long register(Long cameraId, String startedAt) {
        return recordingRegisterService
                .registerRecording(new RecordingRegisterRequest(
                        cameraId, OffsetDateTime.parse(startedAt), null, DUMMY_VIDEO, DUMMY_BOX))
                .id();
    }

    // 캠마다 구역 하나(구역당 캠 1대). 가동 상태는 생성자로 정할 수 없어 직접 넣는다
    private Long saveDummyCamera(String zoneName) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000)",
                dongId,
                zoneName);
        Long zoneId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
        Camera camera = new Camera(zoneId, zoneName + " 캠", CameraSourceType.DUMMY, DUMMY_VIDEO);
        ReflectionTestUtils.setField(camera, "status", CameraStatus.RUNNING);
        ReflectionTestUtils.setField(camera, "runRequested", true);
        return cameraRepository.save(camera).getId();
    }
}
