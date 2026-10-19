/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/media/service/RecordingRegisterServiceTest.java
 * 관련 이슈: #17(FNC-A-026)
 *
 * [무엇] 녹화 등록 서비스의 판단(캠 확인, endedAt 규칙, 경로 규칙, 더미의 이전 반복 닫기, 시각 변환)을 하나씩 확인한다.
 * [가짜와 진짜]
 *   - CameraQueryService, RecordingRepository는 가짜(@Mock): DB 없이 "이 캠은 라이브다", "저장하면 id 900이 된다"를 테스트가 정한다.
 *   - RecordingPathValidator는 진짜(new): 상태 없는 검사기라 진짜를 써도 빠르고, '경로가 틀리면 저장하지 않는다'를 실제로 확인할 수 있다.
 * [이 테스트가 보지 않는 것] 이전 반복을 닫는 UPDATE 쿼리가 DB에서 맞게 동작하는지는 가짜 리포지토리로는 알 수 없다.
 *   그건 RecordingRegisterIntegrationTest(H2)가 본다. 여기서는 '서비스가 그 쿼리를 올바른 인자로 부르는지'만 본다.
 */
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

    // [해석] 경로 예시는 conventions.md '공유 폴더 구조'와 api/analyzer.md 예시를 그대로 썼다(규칙에 맞는 '정상' 경로).
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

    // [해석] 가짜 2개 + 진짜 검사기 1개를 섞어야 해서 @InjectMocks 대신 직접 조립했다(#16 ResultReceiveServiceTest와 같은 이유).
    @BeforeEach
    void setUp() {
        recordingRegisterService =
                new RecordingRegisterService(cameraQueryService, recordingRepository, new RecordingPathValidator());
    }

    // [해석] 라이브 정상 경로. 확인하는 것:
    //        ① 응답 id = 저장 때 채워진 id(900)
    //        ② 저장된 시작 시각: UTC "05:03:00.123456Z" → KST 14:03:00.123 (시간대 변환 + 마이크로초 버림)
    //        ③ 저장된 종료 시각: +09:00 그대로 14:04:00
    //        ④ 라이브라서 '이전 반복 닫기'를 부르지 않음(never)
    //        ArgumentCaptor: 가짜 save에 실제로 넘어간 Recording을 붙잡아 안의 값을 꺼내 본다.
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

    // [해석] FNC-A-026 처리 순서 2. 더미 녹화(endedAt 없음)를 저장하면 '이번 startedAt'(밀리초 500까지)으로
    //        이전 반복을 닫으라고 리포지토리를 불러야 한다. 인자가 정확한지(캠 7, 14:05:00.500)까지 본다.
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

    // [해석] 예외 "없는 캠: 404"의 서비스 쪽. 조회가 빈 값이면 CameraNotFoundException을 던지고, 저장은 하지 않는다.
    //        삭제된 캠도 조회가 빈 값을 주므로 같은 경로를 탄다(삭제 여부는 리포지토리 쿼리가 거름).
    @Test
    @DisplayName("FNC-A-026 없거나 삭제된 캠이면 CameraNotFoundException")
    void throwsWhenCameraNotFound() {
        given(cameraQueryService.findSourceTypeOfExistingCamera(CAMERA_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID, OffsetDateTime.parse("2026-10-12T14:05:00+09:00"), null, DUMMY_VIDEO, DUMMY_BOX)))
                .isInstanceOf(CameraNotFoundException.class);
        verify(recordingRepository, never()).save(any());
    }

    // [해석] 아래 세 테스트는 결정 5의 endedAt 규칙 1·2·3. 셋 다 '400 예외 + 저장하지 않음'을 함께 본다.
    //        규칙 1: 더미인데 endedAt이 있음.
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

    // [해석] 규칙 2: 라이브인데 endedAt이 없음.
    @Test
    @DisplayName("FNC-A-026 라이브 캠인데 endedAt이 없으면 400")
    void rejectsLiveWithoutEndedAt() {
        givenSourceType(CameraSourceType.LIVE);

        assertThatThrownBy(() -> recordingRegisterService.registerRecording(new RecordingRegisterRequest(
                        CAMERA_ID, OffsetDateTime.parse("2026-10-12T14:03:00+09:00"), null, LIVE_VIDEO, LIVE_BOX)))
                .isInstanceOf(RecordingRegisterException.class);
        verify(recordingRepository, never()).save(any());
    }

    // [해석] 규칙 3: endedAt이 startedAt보다 늦지 않음. '같은 시각'을 골랐다 — 앞선 경우(음수 길이)보다 실수하기 쉬운 경계라서.
    //        구현이 >=가 아니라 >(isAfter)로 비교하는지가 이 테스트로 확인된다.
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

    // [해석] 경로 규칙은 RecordingPathValidatorTest가 항목별로 본다. 여기서는 '서비스가 검사기를 저장 전에 부르는지'만 확인한다.
    //        경로 하나만 틀리게('..' 포함) 하고 나머지는 정상으로 두어, 실패 원인이 경로뿐이게 했다.
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

    // [해석] 가짜 캠 조회가 '이 입력 방식의 캠이 있다'고 답하게 설정하는 도우미.
    private void givenSourceType(CameraSourceType sourceType) {
        given(cameraQueryService.findSourceTypeOfExistingCamera(CAMERA_ID)).willReturn(Optional.of(sourceType));
    }

    // 실제 DB처럼 저장할 때 id가 채워지게 한다
    // [해석] 가짜 save는 기본적으로 null을 돌려준다. willAnswer로 '넘겨받은 Recording에 id 900을 넣어 그대로 돌려주기'를 흉내 내야
    //        서비스가 recording.getId()로 응답을 만들 수 있다. Recording에 setter가 없어 id는 리플렉션으로 넣는다.
    private void givenSavedRecordingGetsId() {
        given(recordingRepository.save(any(Recording.class))).willAnswer(invocation -> {
            Recording recording = invocation.getArgument(0);
            ReflectionTestUtils.setField(recording, "id", RECORDING_ID);
            return recording;
        });
    }
}
