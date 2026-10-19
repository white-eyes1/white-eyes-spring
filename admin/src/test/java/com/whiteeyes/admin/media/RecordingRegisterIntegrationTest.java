package com.whiteeyes.admin.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    private Long dummyCameraId;

    @BeforeEach
    void setUp() {
        dummyCameraId = saveDummyCamera(3L);
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
        Long otherCameraId = saveDummyCamera(4L);
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

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
    private Long saveDummyCamera(Long zoneId) {
        Camera camera = BeanUtils.instantiateClass(Camera.class);
        ReflectionTestUtils.setField(camera, "zoneId", zoneId);
        ReflectionTestUtils.setField(camera, "sourceType", CameraSourceType.DUMMY);
        ReflectionTestUtils.setField(camera, "status", CameraStatus.RUNNING);
        ReflectionTestUtils.setField(camera, "runRequested", true);
        return cameraRepository.save(camera).getId();
    }
}
