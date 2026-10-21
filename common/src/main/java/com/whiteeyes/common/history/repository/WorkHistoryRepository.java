package com.whiteeyes.common.history.repository;

import com.whiteeyes.common.history.entity.WorkHistory;
import org.springframework.data.repository.Repository;

/** 작업 기록은 쌓기만 하므로 저장(save)만 연다. 수정·삭제 메서드가 없다(FNC-C-026). */
public interface WorkHistoryRepository extends Repository<WorkHistory, Long> {

    WorkHistory save(WorkHistory workHistory);
}
