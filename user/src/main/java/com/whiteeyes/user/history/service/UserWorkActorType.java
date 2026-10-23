package com.whiteeyes.user.history.service;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;
import org.springframework.stereotype.Component;

/** 사용자 앱에서 common 기능이 남기는 작업 기록(FNC-C-026)의 행동한 계정은 사용자다. */
@Component
public class UserWorkActorType implements WorkActorTypeProvider {

    @Override
    public ActorType actorType() {
        return ActorType.USER;
    }
}
