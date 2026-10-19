/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/stat/service/ResultReceiveServiceTest.java
 * 관련 이슈: #16(FNC-A-024)
 *
 * [무엇] 결과 수신 서비스의 처리 순서 1~4가 맞게 이어지는지 확인하는 테스트.
 * [무엇은 가짜(mock)이고 무엇은 진짜인가]
 *   - CameraQueryService는 가짜(@Mock): DB가 필요한 부분이라, "이 캠은 받을 캠, 저 캠은 아님"을 테스트가 직접 정해 준다.
 *   - MinuteAccumulatorStore, LastResultTimeStore는 진짜: 메모리 객체라 진짜를 써도 빠르고, 실제로 값이 들어갔는지
 *     closeMinute로 꺼내 확인할 수 있다. 가짜로 두면 '호출됐는지'만 알 수 있고 '제대로 쌓였는지'는 모른다.
 */
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

// [해석] MockitoExtension: @Mock 필드를 테스트마다 새 가짜로 만들어 준다. 또 '정해 놓고(given) 안 쓴 설정'이 있으면
//        테스트를 실패시켜(strict stubs) 쓸데없는 준비 코드가 남지 않게 한다.
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

    // [해석] @InjectMocks 대신 직접 new로 조립했다. 진짜 객체(저장소 2개)와 가짜(@Mock)를 섞어 넣어야 해서,
    //        무엇이 들어가는지 눈에 보이게 쓰는 편이 헷갈리지 않는다.
    @BeforeEach
    void setUp() {
        minuteAccumulatorStore = new MinuteAccumulatorStore();
        lastResultTimeStore = new LastResultTimeStore();
        resultReceiveService =
                new ResultReceiveService(cameraQueryService, minuteAccumulatorStore, lastResultTimeStore);
    }

    // [해석] 처리 순서 1. 캠 7은 받을 캠(구역 3), 캠 8은 아님(맵에 없음 = 정지·삭제·없음).
    //        확인 1: 조회는 '한 번', 묶음의 캠 id를 모두 모은 Set으로 했는가(결정 3, 항목마다 조회하지 않음)
    //        확인 2: 구역 3 바구니에는 캠 7의 결과(42)만 있는가
    //        확인 3: 버린 캠 8은 마지막 결과 시각도 없는가
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

    // [해석] 시간대 변환. UTC(Z)로 05:03:05.500이 오면 KST로 14:03:05여야 한다.
    //        - 14:03 바구니에 들어갔는가 → 분을 KST로 계산했는가
    //        - 마지막 결과 시각이 정확히 14:03:05인가 → .500(밀리초)을 잘라 냈는가
    //        분석 서버는 +09:00으로 보내지만, 계약 형식만 맞으면 어떤 오프셋이 와도 같은 결과가 나와야 안전하다.
    @Test
    @DisplayName("FNC-A-024 at을 KST로 바꾸고 초 단위로 잘라 그 분의 누적기에 넣음")
    void convertsAtToKstSecond() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));

        resultReceiveService.receiveResults(List.of(result(CAMERA_ID, "2026-10-12T05:03:05.500Z", 42)));

        assertThat(minuteAccumulatorStore.closeMinute(ZONE_ID, MINUTE)).isPresent();
        assertThat(lastResultTimeStore.findLastResultAt(CAMERA_ID)).contains(MINUTE.withSecond(5));
    }

    // [해석] 처리 순서 4. 07초가 먼저, 재전송된 05초가 나중에 같은 묶음으로 온 상황.
    //        묶음 안의 순서와 상관없이 '가장 늦은 at'(07초)이 남아야 한다(검토 필요 해석 2).
    @Test
    @DisplayName("FNC-A-024 마지막 결과 시각은 받은 결과 중 가장 늦은 at")
    void keepsLatestResultTime() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));

        resultReceiveService.receiveResults(List.of(
                result(CAMERA_ID, "2026-10-12T14:03:07+09:00", 42),
                result(CAMERA_ID, "2026-10-12T14:03:05+09:00", 40)));

        assertThat(lastResultTimeStore.findLastResultAt(CAMERA_ID)).contains(MINUTE.withSecond(7));
    }

    // [해석] 버린 결과로는 시각을 갱신하지 않는다. 마감된 분의 결과는 '캠이 지금 살아 있다'는 근거가 아니다.
    //        이걸 지키지 않으면 늦은 재전송분 때문에 FNC-A-035가 끊긴 캠을 정상으로 잘못 볼 수 있다.
    @Test
    @DisplayName("FNC-A-024 마감된 분이라 버린 결과는 마지막 결과 시각에 반영하지 않음")
    void doesNotUpdateResultTimeForDroppedResult() {
        given(cameraQueryService.findZoneIdsOfReceivingCameras(anySet())).willReturn(Map.of(CAMERA_ID, ZONE_ID));
        minuteAccumulatorStore.closeMinute(ZONE_ID, MINUTE);

        resultReceiveService.receiveResults(List.of(result(CAMERA_ID, "2026-10-12T14:03:30+09:00", 42)));

        assertThat(lastResultTimeStore.findLastResultAt(CAMERA_ID)).isEmpty();
    }

    // [해석] DTO → 내부 값 변환에서 방향이 뒤바뀌거나(N↔S) 값이 빠지지 않는지. in과 out에 서로 다른 값을 넣어
    //        둘이 섞이면 바로 드러나게 했다.
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

    // [해석] 캠·시각·인원만 다르고 나머지(유입·유출 0, 체류 없음)는 같은 요청을 짧게 만드는 도우미.
    private ResultReceiveRequest result(long cameraId, String at, int personCount) {
        DirectionCount none = new DirectionCount(0, 0, 0, 0);
        return new ResultReceiveRequest(cameraId, OffsetDateTime.parse(at), personCount, none, none, List.of());
    }
}
