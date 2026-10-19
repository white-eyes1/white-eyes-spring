/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/camera/service/CameraQueryService.java
 * 관련 이슈: #16(FNC-A-024), #17(FNC-A-026) — TODO(#11) 임시 코드(#16·#17 브랜치에서 같은 내용으로 유지)
 *
 * [무엇] 다른 도메인(#16의 stat, #17의 media)이 캠 정보가 필요할 때 부르는 camera 도메인의 조회 서비스.
 *   메서드마다 그 도메인에 필요한 값만 돌려준다: stat에는 cameraId → zoneId, media에는 입력 방식(LIVE·DUMMY).
 * [왜 따로 만들었나] package-structure.md 3-2 의존 규칙: "다른 도메인이 필요하면 그 도메인의 service만 부른다.
 *   다른 도메인의 repository·엔티티를 직접 쓰지 않는다." 분석 결과 수신은 stat 도메인이라 CameraRepository를 직접 부르면
 *   규칙 위반이다. 그래서 camera 도메인 안에 '창구'를 하나 만들고 stat은 이 창구만 부른다.
 * [염두에 둔 것]
 *   - 엔티티(Camera)를 밖으로 내보내지 않고 Map<cameraId, zoneId>라는 단순한 값만 돌려준다.
 *     stat 쪽이 Camera의 다른 필드나 메서드에 기대지 않게 해서, #11이 엔티티를 바꿔도 stat 코드는 영향이 없다.
 *   - 원래는 #11이 만들 CameraService에 들어갈 메서드라 TODO(#11)로 표시했다.
 */
package com.whiteeyes.admin.camera.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.repository.CameraRepository;

// TODO(#11): 캠 등록(FNC-A-018)에서 CameraService가 생기면 그쪽으로 옮긴다
@Service
public class CameraQueryService {

    // [해석] 의존성은 생성자 주입 + private final(code-conventions 3-3). 필드 주입(@Autowired 필드)을 쓰지 않는 이유:
    //        final이라 바뀌지 않음이 보장되고, 테스트에서 new로 직접 만들거나 @InjectMocks로 넣기 쉽다.
    private final CameraRepository cameraRepository;

    public CameraQueryService(CameraRepository cameraRepository) {
        this.cameraRepository = cameraRepository;
    }

    /**
     * 분석 결과를 받을 캠(삭제되지 않았고 run_requested=1)만 골라 cameraId → zoneId로 돌려준다(FNC-A-024).
     */
    // [해석] readOnly = true: 읽기만 하는 트랜잭션이라고 알려서 JPA가 변경 감지(dirty checking)를 하지 않게 한다. 조금 가볍다.
    // [해석] Set으로 받는 이유: 한 묶음(최대 2,000건)에 같은 캠이 여러 번 나오므로 중복을 없애고 한 번만 조회하려고.
    @Transactional(readOnly = true)
    public Map<Long, Long> findZoneIdsOfReceivingCameras(Set<Long> cameraIds) {
        // [해석] 명세에는 "캠 조회(캐시)"라고 되어 있지만, 사용자와 정한 대로 캐시 대신 요청 하나에 쿼리 한 번(IN 조회)을 쓴다.
        //        캐시를 두면 캠을 정지·삭제한 직후 캐시가 갱신될 때까지 결과가 잘못 들어갈 수 있다. 1초에 1번 쿼리라 부담도 적다.
        //        (PR에 '검토 필요'로 표시한 해석)
        return cameraRepository.findAllById(cameraIds).stream()
                // [해석] FNC-A-024 처리 순서 1 "삭제됐거나 run_requested=0이면 버림". 걸러진 캠은 맵에 없으므로
                //        호출한 쪽에서는 '맵에 없음 = 버릴 캠'으로만 판단하면 된다. 존재하지 않는 id도 findAllById 결과에
                //        아예 없으니 같은 방식으로 버려진다.
                .filter(camera -> camera.getDeletedAt() == null && camera.isRunRequested())
                // [해석] 캠 id → 구역 id. '구역당 캠 1대'라서 키가 겹칠 일이 없다(겹치면 toMap이 예외를 던져 바로 드러난다).
                .collect(Collectors.toMap(Camera::getId, Camera::getZoneId));
    }

    /**
     * 삭제되지 않은 캠의 입력 방식(LIVE·DUMMY)을 돌려준다. 없거나 삭제된 캠이면 빈 값(FNC-A-026).
     * 정지된 캠(run_requested=0)도 돌려준다 — 라이브 캠은 정지한 뒤에 마지막 녹화를 등록하기 때문(FNC-A-021).
     */
    // [해석] #17에서 추가. 녹화 등록(media 도메인)은 "이 캠이 있는가"와 "라이브인가 더미인가" 두 가지만 알면 된다.
    //        그래서 엔티티 대신 입력 방식 하나만 Optional로 돌려준다. 빈 값이면 호출한 쪽이 404(없는 캠)로 처리한다.
    //        위 메서드와 달리 run_requested는 보지 않는다(사용자와 합의한 결정 4). 라이브 캠을 정지하면 분석 서버가
    //        녹화 중이던 파일을 닫아 정지 '뒤에' 등록하고, 실패하면 다시 보내므로 정지된 캠을 거절하면 마지막 녹화가 사라진다.
    //        삭제된 캠은 #15와 같은 기준으로 '없는 캠'으로 본다(findByIdAndDeletedAtIsNull 재사용, PR에 '검토 필요').
    //        CameraRepository에 새 메서드를 만들지 않고 #15의 것을 재사용해, 공유 파일인 리포지토리를 바꾸지 않았다.
    @Transactional(readOnly = true)
    public Optional<CameraSourceType> findSourceTypeOfExistingCamera(Long cameraId) {
        return cameraRepository.findByIdAndDeletedAtIsNull(cameraId).map(Camera::getSourceType);
    }
}
