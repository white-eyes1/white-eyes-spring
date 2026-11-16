package com.whiteeyes.common.history.repository;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.history.entity.WorkTargetType;

/**
 * 작업 기록은 쌓기만 하므로 저장(save)과 조회만 연다. 수정·삭제 메서드가 없다(FNC-C-026).
 * 조회는 대상별 이력(이벤트 처리 이력 FNC-A-037)에 쓴다.
 */
// JpaRepository 대신 마커 인터페이스 Repository를 상속해, 아래 선언한 메서드만 열고 delete 같은 기본 메서드는 일부러 노출하지 않는다
public interface WorkHistoryRepository extends Repository<WorkHistory, Long> {

    /** 한 건을 저장한다. */
    WorkHistory save(WorkHistory workHistory);

    /** 대상·행동으로 모은 기록을 오래된 순으로 읽는다. 시각이 같으면 번호 순으로 정렬해 순서가 흔들리지 않게 한다. */
    List<WorkHistory> findByTargetTypeAndTargetIdAndActionOrderByCreatedAtAscIdAsc(
            WorkTargetType targetType, Long targetId, WorkAction action);
}
