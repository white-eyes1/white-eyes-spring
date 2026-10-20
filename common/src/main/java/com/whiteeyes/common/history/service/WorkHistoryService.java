package com.whiteeyes.common.history.service;

import com.whiteeyes.common.history.dto.WorkHistoryCommand;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.history.repository.WorkHistoryRepository;
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
}
