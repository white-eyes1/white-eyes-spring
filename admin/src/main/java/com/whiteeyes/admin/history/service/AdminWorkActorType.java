package com.whiteeyes.admin.history.service;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;
import org.springframework.stereotype.Component;

/** 관리자 앱에서 common 기능이 남기는 작업 기록(FNC-C-026)의 행동한 계정은 관리자다. */
@Component
public class AdminWorkActorType implements WorkActorTypeProvider {

    @Override
    public ActorType actorType() {
        return ActorType.ADMIN;
    }
}
