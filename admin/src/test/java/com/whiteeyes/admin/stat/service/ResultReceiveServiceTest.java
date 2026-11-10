package com.whiteeyes.admin.stat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest.DirectionCount;

/** 분석 결과 수신(FNC-A-024) */
@ExtendWith(MockitoExtension.class)
class ResultReceiveServiceTest {

    private static final long CAMERA_ID = 7L;
    private static final long ZONE_ID = 3L;
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 10, 12, 14, 3);

    @Mock
    private CameraQueryService cameraQueryService;

    private MinuteAccumulatorStore minuteAccumulatorStore;
    private LastResultTimeStore lastResultTimeStore;
    private ResultReceiveService resultReceiveService;

    @BeforeEach
    void setUp() {
        minuteAccumulatorStore = new MinuteAccumulatorStore();
        lastResultTimeStore = new LastResultTimeStore();
        resultReceiveService =
                new ResultReceiveService(cameraQueryService, minuteAccumulatorStore, lastResultTimeStore);
    }

    @Test
    @DisplayName("FNC-A-024 묶음 안의 캠을 한 번에 조회하고 결과를 받을 캠이 아니면 버림")
    void dropsResultsOfNonReceivingCameras() {
        long stoppedCameraId = 8L;
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));

        resultReceiveService.receiveResults(List.of(
                result(CAMERA_ID, "2026-10-12T14:03:05+09:00", 42),
                result(stoppedCameraId, "2026-10-12T14:03:05+09:00", 10)));

        verify(cameraQueryService).findZoneIdsOfReceivingCameras(Set.of(CAMERA_ID, stoppedCameraId));
        MinuteAccumulator accumulator =
                minuteAccumulatorStore.closeMinute(ZONE_ID, MINUTE).orElseThrow();
        assertThat(accumulator.getPersonCounts()).containsExactly(42);
        assertThat(lastResultTimeStore.findLastResultAt(stoppedCameraId)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-024 at을 KST로 바꾸고 초 단위로 잘라 그 분의 누적기에 넣음")
    void convertsAtToKstSecond() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));

        resultReceiveService.receiveResults(List.of(result(CAMERA_ID, "2026-10-12T05:03:05.500Z", 42)));

        assertThat(minuteAccumulatorStore.closeMinute(ZONE_ID, MINUTE)).isPresent();
        assertThat(lastResultTimeStore.findLastResultAt(CAMERA_ID)).contains(MINUTE.withSecond(5));
    }

    @Test
    @DisplayName("FNC-A-024 마지막 결과 시각은 받은 결과 중 가장 늦은 at")
    void keepsLatestResultTime() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));

        resultReceiveService.receiveResults(List.of(
                result(CAMERA_ID, "2026-10-12T14:03:07+09:00", 42),
                result(CAMERA_ID, "2026-10-12T14:03:05+09:00", 40)));

        assertThat(lastResultTimeStore.findLastResultAt(CAMERA_ID)).contains(MINUTE.withSecond(7));
    }

    @Test
    @DisplayName("FNC-A-024 마감된 분이라 버린 결과는 마지막 결과 시각에 반영하지 않음")
    void doesNotUpdateResultTimeForDroppedResult() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));
        minuteAccumulatorStore.closeMinute(ZONE_ID, MINUTE);

        resultReceiveService.receiveResults(List.of(result(CAMERA_ID, "2026-10-12T14:03:30+09:00", 42)));

        assertThat(lastResultTimeStore.findLastResultAt(CAMERA_ID)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-024 방향별 유입·유출과 체류 시간을 그대로 넘김")
    void passesDirectionCountsAndDwell() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));

        resultReceiveService.receiveResults(List.of(new ResultReceiveRequest(
                CAMERA_ID,
                OffsetDateTime.parse("2026-10-12T14:03:05+09:00"),
                42,
                new DirectionCount(1, 0, 0, 2),
                new DirectionCount(0, 3, 0, 0),
                List.of(305.2, 41.0))));

        MinuteAccumulator accumulator =
                minuteAccumulatorStore.closeMinute(ZONE_ID, MINUTE).orElseThrow();
        assertThat(accumulator.getInTotal()).isEqualTo(new DirectionCounts(1, 0, 0, 2));
        assertThat(accumulator.getOutTotal()).isEqualTo(new DirectionCounts(0, 3, 0, 0));
        assertThat(accumulator.getDwellCount()).isEqualTo(2);
    }

    private ResultReceiveRequest result(long cameraId, String at, int personCount) {
        DirectionCount none = new DirectionCount(0, 0, 0, 0);
        return new ResultReceiveRequest(cameraId, OffsetDateTime.parse(at), personCount, none, none, List.of());
    }
}
