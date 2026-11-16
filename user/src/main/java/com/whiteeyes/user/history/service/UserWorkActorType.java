package com.whiteeyes.user.history.service;

import org.springframework.stereotype.Component;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;

/** 사용자 앱에서 common 기능이 남기는 작업 기록(FNC-C-026)의 행동한 계정은 사용자다. */
@Component
public class UserWorkActorType implements WorkActorTypeProvider {

    /**
     * 작업 기록에 남길 행동 주체 종류. 사용자 앱에서는 항상 USER다(관리자 앱은 ADMIN을 돌려주는 별도 구현을 쓴다).
     */
    @Override
    public ActorType actorType() {
        return ActorType.USER;
    }
}
