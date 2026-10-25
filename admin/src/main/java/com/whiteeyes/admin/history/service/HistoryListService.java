package com.whiteeyes.admin.history.service;

import com.whiteeyes.admin.history.dto.HistoryListItem;
import com.whiteeyes.admin.history.dto.HistorySearchRequest;
import com.whiteeyes.admin.history.repository.WorkHistoryQueryRepository;
import com.whiteeyes.admin.history.repository.WorkHistoryQueryRepository.Condition;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.web.PageResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 작업 기록 조회(FNC-A-053): 기간·계정·행동 종류로 거른 기록을 최신순으로 한 페이지씩 돌려준다.
 * 아이디는 기록 당시 값(actor_login_id)을 그대로 보여 주므로 계정 테이블을 조인하지 않는다.
 * 조회는 작업 기록을 남기지 않는다(FNC-C-026 '로그인·조회는 기록하지 않음').
 */
@Service
@RequiredArgsConstructor
public class HistoryListService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final WorkHistoryQueryRepository workHistoryQueryRepository;
    private final JsonMapper jsonMapper;

    /** @throws BusinessException 시작 시각이 끝 시각보다 앞서지 않으면(400 INVALID_INPUT) */
    @Transactional(readOnly = true)
    public PageResponse<HistoryListItem> list(HistorySearchRequest request) {
        validate(request);
        Condition condition = new Condition(
                toKst(request.from()), toKst(request.to()), request.actorType(), request.loginId(), request.action());

        long total = workHistoryQueryRepository.count(condition);
        List<HistoryListItem> items = workHistoryQueryRepository.findPage(condition, request.page(), request.size())
                .stream()
                .map(this::toItem)
                .toList();
        return PageResponse.of(items, request.page(), request.size(), total);
    }

    private void validate(HistorySearchRequest request) {
        if (request.from() != null && request.to() != null && !request.from().isBefore(request.to())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "시작 시각은 끝 시각보다 앞이어야 합니다.");
        }
    }

    private HistoryListItem toItem(WorkHistory history) {
        return new HistoryListItem(
                history.getId(),
                // DB에는 KST를 오프셋 없이 저장하므로 응답에서 +09:00을 붙인다
                history.getCreatedAt().atZone(KST).toOffsetDateTime(),
                history.getActorType(),
                history.getActorLoginId(),
                history.getAction(),
                history.getTargetType(),
                history.getTargetId(),
                history.getTargetName(),
                parseDetail(history.getDetail()));
    }

    // detail은 JSON 칼럼이라 저장된 문자열을 그대로 객체로 내보낸다
    private JsonNode parseDetail(String detail) {
        return detail == null ? null : jsonMapper.readTree(detail);
    }

    private static LocalDateTime toKst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
