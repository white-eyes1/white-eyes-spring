package com.whiteeyes.admin.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingExpiredException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.service.ZoneCameraService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/** 녹화 보관 기간 설정(RECORDING_RETENTION_DAYS)을 바꾸면 다시보기·파일 전송의 만료 경계가 같이 바뀐다(#182). */
@ExtendWith(MockitoExtension.class)
class RecordingRetentionConfigTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);
    private static final Long ZONE_ID = 1L;
    private static final List<Long> CAMERA_IDS = List.of(10L);

    @Mock
    private ZoneCameraService zoneCameraService;

    @Mock
    private RecordingRepository recordingRepository;

    private static RetentionProperties days(int recordingDays) {
        return new RetentionProperties(90, recordingDays);
    }

    @Test
    @DisplayName("#182 다시보기 찾기: 보관 일수 경계(정확히 N일 전은 안, 1초 더 앞은 만료)가 설정을 따른다")
    void playbackFind_findAt_boundaryFollowsSetting() {
        given(zoneCameraService.findCameraIdsInZone(ZONE_ID)).willReturn(CAMERA_IDS);
        given(recordingRepository.findContaining(eq(CAMERA_IDS), any(), any(Limit.class)))
                .willReturn(List.of(recording(NOW.minusDays(40))));

        for (int retention : new int[] {7, 30}) {
            PlaybackFindService service = new PlaybackFindService(zoneCameraService, recordingRepository, days(retention));
            LocalDateTime edge = NOW.minusDays(retention);

            assertThatCode(() -> service.findAt(ZONE_ID, edge, NOW, "")).doesNotThrowAnyException();
            assertThatThrownBy(() -> service.findAt(ZONE_ID, edge.minusSeconds(1), NOW, ""))
                    .isInstanceOf(RecordingExpiredException.class);
        }
    }

    @Test
    @DisplayName("#182 다시보기 찾기: latest 조회는 설정한 보관 일수 전부터 찾는다")
    void playbackFind_findLatest_sinceFollowsSetting() {
        given(zoneCameraService.findCameraIdsInZone(ZONE_ID)).willReturn(CAMERA_IDS);
        given(recordingRepository.findFirstByCameraIdInAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
                        eq(CAMERA_IDS), any()))
                .willReturn(Optional.of(recording(NOW.minusHours(1))));

        for (int retention : new int[] {7, 30}) {
            new PlaybackFindService(zoneCameraService, recordingRepository, days(retention))
                    .findLatest(ZONE_ID, NOW, "");

            verify(recordingRepository)
                    .findFirstByCameraIdInAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
                            CAMERA_IDS, NOW.minusDays(retention));
        }
    }

    @Test
    @DisplayName("#182 녹화 파일 전송: 보관 일수 경계(정확히 N일 전은 안, 1초 더 앞은 만료)가 설정을 따른다")
    void recordingFile_boundaryFollowsSetting() {
        for (int retention : new int[] {7, 30}) {
            RecordingFileService service = new RecordingFileService(recordingRepository, days(retention));
            LocalDateTime edge = NOW.minusDays(retention);

            given(recordingRepository.findById(1L)).willReturn(Optional.of(recording(edge)));
            assertThat(service.videoRedirect(1L, NOW)).startsWith("/protected/media/");

            given(recordingRepository.findById(1L)).willReturn(Optional.of(recording(edge.minusSeconds(1))));
            assertThatThrownBy(() -> service.videoRedirect(1L, NOW)).isInstanceOf(RecordingExpiredException.class);
            assertThatThrownBy(() -> service.boxesRedirect(1L, NOW)).isInstanceOf(RecordingExpiredException.class);
        }
    }

    @Test
    @DisplayName("#182 구간 조회: 범위 시작이 보관 일수보다 앞이면 설정한 경계로 자르고, 전부 밖이면 조회하지 않는다")
    void playbackRanges_cutoffFollowsSetting() {
        given(zoneCameraService.findCameraIdsInZone(ZONE_ID)).willReturn(CAMERA_IDS);
        given(recordingRepository.findOverlapping(eq(CAMERA_IDS), any(), any())).willReturn(List.of());

        for (int retention : new int[] {7, 30}) {
            PlaybackRangesService service = new PlaybackRangesService(zoneCameraService, recordingRepository, days(retention));
            LocalDateTime edge = NOW.minusDays(retention);

            // 경계를 걸친 범위: 시작이 경계로 잘린다
            service.findRanges(ZONE_ID, edge.minusHours(1), edge.plusHours(1), NOW);
            verify(recordingRepository).findOverlapping(CAMERA_IDS, edge, edge.plusHours(1));
        }

        // 7일 설정에서 8일 전 범위는 전부 보관 기간 밖이라 DB를 보지 않는다
        PlaybackRangesService sevenDays = new PlaybackRangesService(zoneCameraService, recordingRepository, days(7));
        org.mockito.Mockito.clearInvocations(recordingRepository);
        assertThat(sevenDays.findRanges(ZONE_ID, NOW.minusDays(8), NOW.minusDays(8).plusHours(1), NOW).ranges())
                .isEmpty();
        verifyNoInteractions(recordingRepository);
    }

    private static Recording recording(LocalDateTime startedAt) {
        return Recording.register(10L, startedAt, startedAt.plusMinutes(1), "dummy/videos/a.mp4", "dummy/boxes/a.json.gz");
    }
}
