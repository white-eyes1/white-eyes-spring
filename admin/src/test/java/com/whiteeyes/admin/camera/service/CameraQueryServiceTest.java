package com.whiteeyes.admin.camera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.repository.CameraRepository;

/** 분석 결과 수신(FNC-A-024) */
@ExtendWith(MockitoExtension.class)
class CameraQueryServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @InjectMocks
    private CameraQueryService cameraQueryService;

    @Test
    @DisplayName("FNC-A-024 삭제되지 않았고 run_requested=1인 캠만 cameraId → zoneId로 돌려줌")
    void returnsOnlyReceivingCameras() {
        Camera running = camera(7L, 3L, true, null);
        Camera stopped = camera(8L, 4L, false, null);
        Camera deleted = camera(9L, 5L, true, LocalDateTime.of(2026, 10, 1, 9, 0));
        given(cameraRepository.findAllById(Set.of(7L, 8L, 9L, 10L))).willReturn(List.of(running, stopped, deleted));

        Map<Long, Long> zoneIdByCamera = cameraQueryService.findZoneIdsOfReceivingCameras(Set.of(7L, 8L, 9L, 10L));

        assertThat(zoneIdByCamera).containsExactly(Map.entry(7L, 3L));
    }

    @Test
    @DisplayName("FNC-A-026 삭제되지 않은 캠이면 정지 상태여도 입력 방식을 돌려줌")
    void returnsSourceTypeOfStoppedCamera() {
        Camera stopped = camera(8L, 4L, false, null);
        ReflectionTestUtils.setField(stopped, "sourceType", CameraSourceType.LIVE);
        given(cameraRepository.findByIdAndDeletedAtIsNull(8L)).willReturn(Optional.of(stopped));

        assertThat(cameraQueryService.findSourceTypeOfExistingCamera(8L)).contains(CameraSourceType.LIVE);
    }

    @Test
    @DisplayName("FNC-A-026 없거나 삭제된 캠이면 빈 값")
    void returnsEmptyWhenCameraMissing() {
        given(cameraRepository.findByIdAndDeletedAtIsNull(9L)).willReturn(Optional.empty());

        assertThat(cameraQueryService.findSourceTypeOfExistingCamera(9L)).isEmpty();
    }

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
    private Camera camera(long id, long zoneId, boolean runRequested, LocalDateTime deletedAt) {
        Camera camera = BeanUtils.instantiateClass(Camera.class);
        ReflectionTestUtils.setField(camera, "id", id);
        ReflectionTestUtils.setField(camera, "zoneId", zoneId);
        ReflectionTestUtils.setField(camera, "runRequested", runRequested);
        ReflectionTestUtils.setField(camera, "deletedAt", deletedAt);
        return camera;
    }
}
