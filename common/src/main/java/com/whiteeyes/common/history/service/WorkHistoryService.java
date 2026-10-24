package com.whiteeyes.common.history.service;

import com.whiteeyes.common.history.dto.WorkHistoryCommand;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.repository.WorkHistoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 모든 작업 기록은 이 서비스 하나로 저장한다(FNC-C-026). */
@Service
public class WorkHistoryService {

    private final WorkHistoryRepository workHistoryRepository;

    public WorkHistoryService(WorkHistoryRepository workHistoryRepository) {
        this.workHistoryRepository = workHistoryRepository;
    }

    /**
     * 작업 기록 1행을 저장한다. INSERT만 하며 수정·삭제는 없다.
     * 기록 대상 기능의 서비스 메서드가 성공한 뒤 같은 트랜잭션 안에서 불러야 하고, 저장이 실패하면 원래 작업도 함께 취소된다.
     * 트랜잭션 없이 부르면 기록만 따로 남는 일을 막기 위해 거부한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(WorkHistoryCommand command) {
        workHistoryRepository.save(new WorkHistory(
                command.actorType(),
                command.actorId(),
                command.actorLoginId(),
                command.action(),
                command.targetType(),
                command.targetId(),
                command.targetName(),
                command.detail()));
    }

    /** 한 대상의 한 행동 기록을 오래된 것부터 돌려준다(이벤트 처리 이력, FNC-A-037). 조회라 기록하지 않는다. */
    @Transactional(readOnly = true)
    public List<WorkHistory> findByTarget(WorkTargetType targetType, Long targetId, WorkAction action) {
        return workHistoryRepository.findByTargetTypeAndTargetIdAndActionOrderByCreatedAtAscIdAsc(
                targetType, targetId, action);
    }
}
