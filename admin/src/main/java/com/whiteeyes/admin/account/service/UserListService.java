package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.repository.UserAccountQueryRepository;
import com.whiteeyes.admin.account.repository.UserRow;
import com.whiteeyes.common.web.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 계정을 검색·필터·정렬해 페이지 단위로 돌려준다(FNC-A-002). */
@Service
@RequiredArgsConstructor
public class UserListService {

    private final UserAccountQueryRepository userAccountQueryRepository;
    private final UserActivityService userActivityService;

    /**
     * 세션 정보가 필요 없는 조건이면 DB에서 페이지까지 자르고 그 페이지 계정들의 세션만 읽는다.
     * active 필터·최근 활동 정렬이면 조건에 맞는 계정을 상한까지 읽어 메모리에서 처리한다(FNC-A-002 업무 규칙).
     * 어느 쪽이든 세션 조회는 한 번이다.
     */
    @Transactional(readOnly = true)
    public PageResponse<UserListItem> list(UserSearchRequest request) {
        if (request.isSessionDependent()) {
            return listInMemory(request);
        }
        List<UserRow> rows = userAccountQueryRepository.findPage(request);
        long totalElements = userAccountQueryRepository.count(request);
        UserActivities activities = userActivityService.loadActivities(loginIdsOf(rows));
        return UserListAssembler.assemblePage(rows, totalElements, activities, request);
    }

    private PageResponse<UserListItem> listInMemory(UserSearchRequest request) {
        // 상한 + 1명까지 읽어 상한을 넘었는지 알 수 있게 한다
        List<UserRow> candidates =
                userAccountQueryRepository.findCandidates(request, UserListAssembler.SESSION_DEPENDENT_LIMIT + 1);
        List<UserRow> considered =
                candidates.subList(0, Math.min(candidates.size(), UserListAssembler.SESSION_DEPENDENT_LIMIT));
        UserActivities activities = userActivityService.loadActivities(loginIdsOf(considered));
        return UserListAssembler.assembleInMemory(candidates, activities, request);
    }

    private static List<String> loginIdsOf(List<UserRow> rows) {
        return rows.stream().map(UserRow::loginId).toList();
    }
}
