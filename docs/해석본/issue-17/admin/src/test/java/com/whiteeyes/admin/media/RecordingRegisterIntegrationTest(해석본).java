/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/media/RecordingRegisterIntegrationTest.java
 * 관련 이슈: #17(FNC-A-026) — 이슈 완료 기준 "더미 반복 시 이전 녹화가 닫히는지 확인"
 *
 * [무엇] 서비스 → 리포지토리 → 실제 DB(H2)까지 한 번에 돌려, 이전 반복을 닫는 UPDATE가 진짜로 맞게 동작하는지 확인한다.
 * [왜 통합 테스트인가] 서비스 테스트(가짜 리포지토리)는 'UPDATE를 불렀는지'만 알 뿐, 쿼리 조건(같은 캠, 진행 중, 더 이른 시작)이
 *   맞는지는 모른다. 조건을 잘못 쓰면 엉뚱한 녹화가 닫히는데, 그건 실제 DB에 행을 넣고 결과를 읽어 봐야 잡힌다.
 * [어떻게 DB가 생기나] 테스트 설정(admin/src/test/resources/application.yml)의 H2 메모리 DB + ddl-auto: create-drop.
 *   V파일이 아직 없어서 Hibernate가 엔티티 모양대로 camera·recording 테이블을 만든다(TODO(#11)).
 * [이름] 패키지 media 바로 아래, '{대상}IntegrationTest'. 컨트롤러~DB를 아우르는 테스트라 특정 계층 폴더에 넣지 않았다.
 */
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
// [해석] @SpringBootTest: 관리자 앱 전체를 띄운다(진짜 서비스·리포지토리·DB). 가장 실제에 가깝지만 느리므로 꼭 필요한 것만 여기서 본다.
//        @Transactional: 테스트마다 트랜잭션을 열고 끝나면 되돌린다(롤백). 테스트끼리 넣은 행이 섞이지 않는다.
@SpringBootTest
@Transactional
class RecordingRegisterIntegrationTest {

    private static final String DUMMY_VIDEO = "dummy/videos/a.mp4";
    private static final String DUMMY_BOX = "dummy/boxes/a.json.gz";

    // [해석] 테스트 클래스는 Spring이 만든 빈을 필드로 받는다(@Autowired). 운영 코드의 '생성자 주입' 규칙은 테스트에 강제되지 않는다.
    @Autowired
    private RecordingRegisterService recordingRegisterService;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private CameraRepository cameraRepository;

    private Long dummyCameraId;

    // [해석] 녹화는 존재하는 캠이 있어야 등록된다(없으면 404). 그래서 테스트마다 더미 캠 하나를 먼저 DB에 넣는다.
    @BeforeEach
    void setUp() {
        dummyCameraId = saveDummyCamera(3L);
    }

    // [해석] 이슈 완료 기준 그 자체. 14:00 반복 → 14:05:00.250 반복 순서로 등록하면:
    //        첫 녹화의 ended_at = 두 번째의 started_at(밀리초 250까지 정확히), 두 번째는 아직 진행 중(null).
    //        밀리초를 일부러 넣은 이유: 밀리초가 잘리거나 반올림되면 다시보기 위치가 어긋나는데, 그것까지 함께 확인하려고.
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

    // [해석] 결정 5의 '더 이른 것만 닫기'(검토 필요 해석)를 실제 DB로 확인. 14:05 반복이 먼저 등록되고, 재전송된 14:00 반복이
    //        늦게 도착한 상황이다. 14:00 등록이 14:05 녹화를 닫으면 '끝(14:00)이 시작(14:05)보다 빠른' 녹화가 생긴다.
    //        쿼리의 started_at < :closedAt 조건이 이것을 막는지 본다.
    @Test
    @DisplayName("FNC-A-026 늦게 도착한 이전 반복 등록은 더 나중에 시작한 녹화를 닫지 않음")
    void lateRegistrationDoesNotCloseLaterRecording() {
        Long laterId = register(dummyCameraId, "2026-10-12T14:05:00+09:00");

        register(dummyCameraId, "2026-10-12T14:00:00+09:00");

        assertThat(recordingRepository.findById(laterId).orElseThrow().getEndedAt())
                .isNull();
    }

    // [해석] 쿼리의 camera_id 조건 확인. 같은 영상을 쓰는 더미 캠이 여러 대일 수 있는데(FNC-S-009), 한 캠의 반복이
    //        다른 캠의 재생 중인 녹화를 닫으면 안 된다.
    @Test
    @DisplayName("FNC-A-026 다른 캠의 진행 중인 녹화는 닫지 않음")
    void doesNotCloseOtherCameraRecording() {
        Long otherCameraId = saveDummyCamera(4L);
        Long otherId = register(otherCameraId, "2026-10-12T14:00:00+09:00");

        register(dummyCameraId, "2026-10-12T14:05:00+09:00");

        assertThat(recordingRepository.findById(otherId).orElseThrow().getEndedAt())
                .isNull();
    }

    // [해석] 더미 녹화 등록을 한 줄로 쓰는 도우미. 실제 서비스를 거치므로 캠 확인·경로 검사·이전 반복 닫기가 모두 실제로 돈다.
    private Long register(Long cameraId, String startedAt) {
        return recordingRegisterService
                .registerRecording(new RecordingRegisterRequest(
                        cameraId, OffsetDateTime.parse(startedAt), null, DUMMY_VIDEO, DUMMY_BOX))
                .id();
    }

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
    // [해석] Camera에는 생성 메서드가 없어(#11 몫) 리플렉션으로 NOT NULL 칼럼을 채운 뒤 진짜 리포지토리로 저장한다.
    //        progress는 int 기본값 0이 들어가므로 따로 넣지 않았다. id는 DB가 만든다(IDENTITY).
    private Long saveDummyCamera(Long zoneId) {
        Camera camera = BeanUtils.instantiateClass(Camera.class);
        ReflectionTestUtils.setField(camera, "zoneId", zoneId);
        ReflectionTestUtils.setField(camera, "sourceType", CameraSourceType.DUMMY);
        ReflectionTestUtils.setField(camera, "status", CameraStatus.RUNNING);
        ReflectionTestUtils.setField(camera, "runRequested", true);
        return cameraRepository.save(camera).getId();
    }
}
