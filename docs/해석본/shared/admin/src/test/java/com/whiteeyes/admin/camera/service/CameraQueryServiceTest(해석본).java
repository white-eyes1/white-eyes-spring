/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/camera/service/CameraQueryServiceTest.java
 * 관련 이슈: #16(FNC-A-024 처리 순서 1), #17(FNC-A-026) — #16·#17 브랜치에서 같은 내용으로 유지
 *
 * [무엇] camera 도메인이 다른 도메인에 내주는 조회 두 가지를 확인하는 테스트.
 *   - #16: '결과를 받을 캠'만 골라내는 조건(삭제되지 않음 + run_requested=1)
 *   - #17: 녹화 등록이 쓸 '입력 방식' 조회(정지된 캠도 돌려줌, 없으면 빈 값)
 * [#16 테스트가 하나뿐인 이유] 확인할 조건이 '거르기' 하나라서, 네 가지 경우(정상·정지·삭제·없음)를 한 테스트에 모아
 *   결과 맵에 정상 캠 하나만 남는지 보면 된다.
 */
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

@ExtendWith(MockitoExtension.class)
class CameraQueryServiceTest {

    // [해석] 리포지토리는 가짜. DB 없이 "findAllById를 부르면 이 캠들이 나온다"를 테스트가 정한다.
    @Mock
    private CameraRepository cameraRepository;

    // [해석] @InjectMocks: 위 가짜 리포지토리를 생성자로 넣어 진짜 CameraQueryService를 만든다.
    @InjectMocks
    private CameraQueryService cameraQueryService;

    // [해석] 7: 정상(받음) / 8: run_requested=0(정지) / 9: 삭제됨 / 10: DB에 없음(리포지토리가 돌려주지 않음).
    //        containsExactly로 "7 → 3 하나만, 다른 건 없음"을 한 번에 확인한다.
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

    // [해석] #17 결정 4: 정지된 캠(run_requested=false)이어도 입력 방식을 돌려줘야 한다.
    //        일부러 '정지된 라이브 캠'을 골랐다 — 정지 뒤에 마지막 녹화를 등록하는 바로 그 경우라서.
    @Test
    @DisplayName("FNC-A-026 삭제되지 않은 캠이면 정지 상태여도 입력 방식을 돌려줌")
    void returnsSourceTypeOfStoppedCamera() {
        Camera stopped = camera(8L, 4L, false, null);
        ReflectionTestUtils.setField(stopped, "sourceType", CameraSourceType.LIVE);
        given(cameraRepository.findByIdAndDeletedAtIsNull(8L)).willReturn(Optional.of(stopped));

        assertThat(cameraQueryService.findSourceTypeOfExistingCamera(8L)).contains(CameraSourceType.LIVE);
    }

    // [해석] 리포지토리가 빈 값을 주면(없는 캠 또는 삭제된 캠) 그대로 빈 값. 삭제 여부를 거르는 것은 리포지토리 쿼리
    //        (deleted_at IS NULL)의 몫이라, 여기서는 '빈 값을 그대로 전달하는지'만 본다.
    @Test
    @DisplayName("FNC-A-026 없거나 삭제된 캠이면 빈 값")
    void returnsEmptyWhenCameraMissing() {
        given(cameraRepository.findByIdAndDeletedAtIsNull(9L)).willReturn(Optional.empty());

        assertThat(cameraQueryService.findSourceTypeOfExistingCamera(9L)).isEmpty();
    }

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
    // [해석] Camera는 setter도 공개 생성자도 없다(엔티티 규칙). 테스트용 캠을 만들 정식 방법은 #11이 만들 예정이라,
    //        그 전까지는 BeanUtils(보호된 생성자 호출)와 ReflectionTestUtils(private 필드에 값 넣기)로 만든다.
    //        엔티티 규칙을 테스트 때문에 깨지 않으려고 고른 방법이고, #11 이후에는 이 도우미를 지운다.
    private Camera camera(long id, long zoneId, boolean runRequested, LocalDateTime deletedAt) {
        Camera camera = BeanUtils.instantiateClass(Camera.class);
        ReflectionTestUtils.setField(camera, "id", id);
        ReflectionTestUtils.setField(camera, "zoneId", zoneId);
        ReflectionTestUtils.setField(camera, "runRequested", runRequested);
        ReflectionTestUtils.setField(camera, "deletedAt", deletedAt);
        return camera;
    }
}
