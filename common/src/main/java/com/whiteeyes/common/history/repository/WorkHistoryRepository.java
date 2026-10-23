package com.whiteeyes.common.history.repository;

import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.history.entity.WorkTargetType;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * 작업 기록은 쌓기만 하므로 저장(save)과 조회만 연다. 수정·삭제 메서드가 없다(FNC-C-026).
 * 조회는 대상별 이력(이벤트 처리 이력 FNC-A-037)에 쓴다.
 */
public interface WorkHistoryRepository extends Repository<WorkHistory, Long> {

    WorkHistory save(WorkHistory workHistory);

    List<WorkHistory> findByTargetTypeAndTargetIdAndActionOrderByCreatedAtAscIdAsc(
            WorkTargetType targetType, Long targetId, WorkAction action);
}
