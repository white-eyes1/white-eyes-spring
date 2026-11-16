package com.whiteeyes.admin.history.service;

import org.springframework.stereotype.Component;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.service.WorkActorTypeProvider;

/** 관리자 앱에서 common 기능이 남기는 작업 기록(FNC-C-026)의 행동한 계정은 관리자다. */
@Component
public class AdminWorkActorType implements WorkActorTypeProvider {

    // common 모듈은 관리자·사용자 앱 어느 쪽인지 모르므로, 앱마다 이 구현이 계정 종류를 알려 준다
    @Override
    public ActorType actorType() {
        // 관리자 앱이므로 항상 ADMIN
        return ActorType.ADMIN;
    }
}
