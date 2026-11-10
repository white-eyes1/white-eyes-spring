package com.whiteeyes.common.history.dto;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;

/**
 * 작업 기록 한 건의 입력(FNC-C-026).
 *
 * @param actorType 행동한 계정 종류
 * @param actorId 행동한 계정 번호
 * @param actorLoginId 행동한 계정의 아이디(기록 당시 값)
 * @param action 행동 종류
 * @param targetType 대상 종류. 없으면 null
 * @param targetId 대상 번호. 전체 다운로드처럼 없으면 null
 * @param targetName 기록 당시 대상 이름. 없으면 null
 * @param detail 상세 JSON 문자열. 비밀번호 값은 넣지 않고 '변경됨'만 적는 것은 호출하는 쪽이 지킨다. 없으면 null
 */
public record WorkHistoryCommand(
        ActorType actorType,
        Long actorId,
        String actorLoginId,
        WorkAction action,
        WorkTargetType targetType,
        Long targetId,
        String targetName,
        String detail) {}
