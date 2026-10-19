/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/camera/repository/CameraRepository.java
 * 관련 이슈: #15, #16 — TODO(#11) 임시 코드(두 브랜치에서 같은 내용)
 *
 * [무엇] camera 테이블을 읽고 쓰는 Spring Data JPA 리포지토리. 인터페이스만 선언하면 Spring이 구현체를 만들어 준다.
 * [염두에 둔 것]
 *   - 리포지토리는 '데이터 조회·저장'만 하고 판단은 하지 않는다(package-structure 3-1 계층 표).
 *   - #16은 메서드를 새로 추가하지 않고 JpaRepository가 원래 갖고 있는 findAllById를 쓴다.
 *     여기에 메서드를 하나라도 더하면 #15 브랜치와 내용이 달라져 합칠 때 충돌이 나기 때문이다.
 */
package com.whiteeyes.common.camera.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.camera.entity.Camera;

// TODO(#11): 캠 등록(FNC-A-018)에서 정식으로 만든다. FNC-A-025 구현을 위해 먼저 둔 임시 코드
// [해석] JpaRepository<엔티티, PK 타입>. 이것만으로 findById, findAllById, save, delete 등이 생긴다.
public interface CameraRepository extends JpaRepository<Camera, Long> {

    // [해석] #15용. 메서드 이름 규칙으로 쿼리가 자동 생성된다:
    //        SELECT * FROM camera WHERE id = ? AND deleted_at IS NULL
    //        삭제된 캠도 '없는 캠'(404)으로 보기 위한 조건이다(PR #93에 '검토 필요'로 표시한 해석).
    //        Optional을 반환값에만 쓰는 것은 code-conventions 3-3 규칙. 호출하는 쪽이 '없을 수 있음'을 반드시 처리하게 된다.
    Optional<Camera> findByIdAndDeletedAtIsNull(Long id);
}
