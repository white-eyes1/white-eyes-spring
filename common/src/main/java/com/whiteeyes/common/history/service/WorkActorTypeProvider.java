package com.whiteeyes.common.history.service;

import com.whiteeyes.common.history.entity.ActorType;

/**
 * 작업 기록(FNC-C-026)의 행동한 계정 종류. 두 앱이 함께 쓰는 기능(엑셀 다운로드 등)은 common에서 기록하지만
 * 어느 앱의 계정인지는 common이 알 수 없으므로, 각 앱이 이 인터페이스를 구현한다(사용자 앱 USER, 관리자 앱 ADMIN).
 */
public interface WorkActorTypeProvider {

    ActorType actorType();
}
