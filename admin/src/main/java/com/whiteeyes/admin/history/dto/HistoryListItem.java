package com.whiteeyes.admin.history.dto;

import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 작업 기록 목록의 한 행(api/public.md GET /api/v1/histories, FNC-A-053).
 *
 * @param at 기록 시각(+09:00)
 * @param loginId 행동한 계정의 기록 당시 아이디
 * @param targetType 대상 종류. 없으면 null
 * @param targetId 대상 번호. 전체 다운로드처럼 없으면 null
 * @param targetName 기록 당시 대상 이름. 없으면 null
 * @param detail 행동마다 다른 상세(JSON 객체). 없으면 null
 */
public record HistoryListItem(
        Long id,
        OffsetDateTime at,
        ActorType actorType,
        String loginId,
        WorkAction action,
        WorkTargetType targetType,
        Long targetId,
        String targetName,
        JsonNode detail) {}
