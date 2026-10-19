package com.whiteeyes.admin.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.entity.CameraSourceType;

@ExtendWith(MockitoExtension.class)
class RecordingRegisterServiceTest {

    private static final Long CAMERA_ID = 7L;
    private static final Long RECORDING_ID = 900L;
    private static final String LIVE_VIDEO = "recordings/live/cam7/20261012/cam7_20261012_1403.mp4";
    private static final String LIVE_BOX = "recordings/live/cam7/20261012/cam7_20261012_1403.json.gz";
    private static final String DUMMY_VIDEO = "dummy/videos/a.mp4";
    private static final String DUMMY_BOX = "dummy/boxes/a.json.gz";

    @Mock
    private CameraQueryService cameraQueryService;

    @Mock
    private RecordingRepository recordingRepository;

    private RecordingRegisterService recordingRegisterService;

    @BeforeEach
    void setUp() {
        recordingRegisterService =
                new RecordingRegisterService(cameraQueryService, recordingRepository, new RecordingPathValidator());
    }

    @Test
    @DisplayName("FNC-A-026 라이브 녹화를 KST·밀리초로 저장하고 id를 돌려줌")
    void registersLiveRecording() {
        givenSourceType(CameraSourceType.LIVE);
        givenSavedRecordingGetsId();

        RecordingRegisterResponse response = recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                CAMERA_ID,
                OffsetDateTime.parse("2026-10-12T05:03:00.123456Z"),
                OffsetDateTime.parse("2026-10-12T14:04:00.000+09:00"),
                LIVE_VIDEO,
                LIVE_BOX));

        assertThat(response.id()).isEqualTo(RECORDING_ID);
        ArgumentCaptor<Recording> saved = ArgumentCaptor.forClass(Recording.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 14, 3, 0, 123_000_000));
        assertThat(saved.getValue().getEndedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 14, 4));
        verify(recordingRepository, never()).closeOpenRecordingsStartedBefore(anyLong(), any());
    }

    @Test
    @DisplayName("FNC-A-026 더미 녹화를 저장하면 이전 반복을 이번 startedAt으로 닫음")
    void closesPreviousDummyRecording() {
        givenSourceType(CameraSourceType.DUMMY);
        givenSavedRecordingGetsId();

        recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                CAMERA_ID, OffsetDateTime.parse("2026-10-12T14:05:00.500+09:00"), null, DUMMY_VIDEO, DUMMY_BOX));

        verify(recordingRepository)
                .closeOpenRecordingsStartedBefore(CAMERA_ID, LocalDateTime.of(2026, 10, 12, 14, 5, 0, 500_000_000));
    }

    @Test
    @DisplayName("FNC-A-026 없거나 삭제된 캠이면 CameraNotFoundException")
    void throwsWhenCameraNotFound() {
        given(cameraQueryService.findSourceTypeOfExistingCamera(CAMERA_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID, OffsetDateTime.parse("2026-10-12T14:05:00+09:00"), null, DUMMY_VIDEO, DUMMY_BOX)))
                .isInstanceOf(CameraNotFoundException.class);
        verify(recordingRepository, never()).save(any());
    }

    @Test
    @DisplayName("FNC-A-026 더미 캠인데 endedAt이 있으면 400")
    void rejectsDummyWithEndedAt() {
        givenSourceType(CameraSourceType.DUMMY);

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID,
                        OffsetDateTime.parse("2026-10-12T14:05:00+09:00"),
                        OffsetDateTime.parse("2026-10-12T14:06:00+09:00"),
                        DUMMY_VIDEO,
                        DUMMY_BOX)))
                .isInstanceOf(RecordingRegisterException.class);
        verify(recordingRepository, never()).save(any());
    }

    @Test
    @DisplayName("FNC-A-026 라이브 캠인데 endedAt이 없으면 400")
    void rejectsLiveWithoutEndedAt() {
        givenSourceType(CameraSourceType.LIVE);

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID, OffsetDateTime.parse("2026-10-12T14:03:00+09:00"), null, LIVE_VIDEO, LIVE_BOX)))
                .isInstanceOf(RecordingRegisterException.class);
        verify(recordingRepository, never()).save(any());
    }

    @Test
    @DisplayName("FNC-A-026 endedAt이 startedAt보다 늦지 않으면 400")
    void rejectsEndedAtNotAfterStartedAt() {
        givenSourceType(CameraSourceType.LIVE);

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID,
                        OffsetDateTime.parse("2026-10-12T14:03:00+09:00"),
                        OffsetDateTime.parse("2026-10-12T14:03:00+09:00"),
                        LIVE_VIDEO,
                        LIVE_BOX)))
                .isInstanceOf(RecordingRegisterException.class);
        verify(recordingRepository, never()).save(any());
    }

    @Test
    @DisplayName("FNC-A-026 경로 규칙을 어기면 저장하지 않고 400")
    void rejectsInvalidPathBeforeSaving() {
        givenSourceType(CameraSourceType.DUMMY);

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID,
                        OffsetDateTime.parse("2026-10-12T14:05:00+09:00"),
                        null,
                        "dummy/../secret.mp4",
                        DUMMY_BOX)))
                .isInstanceOf(RecordingRegisterException.class);
        verify(recordingRepository, never()).save(any());
    }

    private void givenSourceType(CameraSourceType sourceType) {
        given(cameraQueryService.findSourceTypeOfExistingCamera(CAMERA_ID)).willReturn(Optional.of(sourceType));
    }

    // 실제 DB처럼 저장할 때 id가 채워지게 한다
    private void givenSavedRecordingGetsId() {
        given(recordingRepository.save(any(Recording.class))).willAnswer(invocation -> {
            Recording recording = invocation.getArgument(0);
            ReflectionTestUtils.setField(recording, "id", RECORDING_ID);
            return recording;
        });
    }
}
