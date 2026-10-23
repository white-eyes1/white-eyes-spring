package com.whiteeyes.common.history.service;

import com.whiteeyes.common.history.dto.WorkHistoryCommand;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.security.LoginAccountResolver;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 두 앱이 함께 쓰는 기능에서 한 일을 작업 기록으로 남긴다(FNC-C-026). 행동한 계정은 로그인 정보에서, 계정 종류는
 * 앱마다 등록한 {@link WorkActorTypeProvider}에서 채운다. 저장은 {@link WorkHistoryService} 하나를 거치므로
 * 부르는 쪽 트랜잭션 안에서 불러야 한다.
 */
@Component
public class WorkRecorder {

    private final LoginAccountResolver loginAccountResolver;
    private final WorkActorTypeProvider actorTypeProvider;
    private final WorkHistoryService workHistoryService;
    private final JsonMapper jsonMapper;

    public WorkRecorder(
            LoginAccountResolver loginAccountResolver,
            WorkActorTypeProvider actorTypeProvider,
            WorkHistoryService workHistoryService,
            JsonMapper jsonMapper) {
        this.loginAccountResolver = loginAccountResolver;
        this.actorTypeProvider = actorTypeProvider;
        this.workHistoryService = workHistoryService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 지금 로그인한 계정을 돌려준다. 로그인하지 않았으면 401로 거부한다.
     * 작업을 시작하기 전에 불러 두면 기록할 수 없는 요청을 일을 한 뒤가 아니라 처음에 거부할 수 있다.
     */
    public LoginAccount requireActor() {
        return loginAccountResolver.require();
    }

    /** 대상 이름은 기록 당시 이름을 넘긴다. detail은 JSON으로 바꿔 저장한다(비밀번호 값은 넣지 않는다). */
    public void record(
            LoginAccount actor,
            WorkAction action,
            WorkTargetType targetType,
            Long targetId,
            String targetName,
            Map<String, ?> detail) {
        workHistoryService.record(new WorkHistoryCommand(
                actorTypeProvider.actorType(),
                actor.accountId(),
                actor.loginId(),
                action,
                targetType,
                targetId,
                targetName,
                detail == null ? null : jsonMapper.writeValueAsString(detail)));
    }
}
