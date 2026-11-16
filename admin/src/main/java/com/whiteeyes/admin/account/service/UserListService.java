package com.whiteeyes.admin.account.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.dto.UserListItem;
import com.whiteeyes.admin.account.dto.UserSearchRequest;
import com.whiteeyes.admin.account.repository.SessionActivityRepository.SessionTable;
import com.whiteeyes.admin.account.repository.UserAccountQueryRepository;
import com.whiteeyes.admin.account.repository.UserRow;
import com.whiteeyes.common.web.PageResponse;

/** 사용자 계정을 검색·필터·정렬해 페이지 단위로 돌려준다(FNC-A-002). */
@Service
@RequiredArgsConstructor
public class UserListService {

    // 사용자 목록을 읽는 Criteria 저장소
    private final UserAccountQueryRepository userAccountQueryRepository;
    // 세션 표로 최근 활동·활동 중 여부를 계산한다
    private final AccountActivityService accountActivityService;

    /**
     * 세션 정보가 필요 없는 조건이면 DB에서 페이지까지 자르고 그 페이지 계정들의 세션만 읽는다.
     * active 필터·최근 활동 정렬이면 조건에 맞는 계정을 상한까지 읽어 메모리에서 처리한다(FNC-A-002 업무 규칙).
     * 어느 쪽이든 세션 조회는 한 번이다.
     */
    @Transactional(readOnly = true)
    public PageResponse<UserListItem> list(UserSearchRequest request) {
        // 세션 정보가 있어야 하는 조건이면 메모리에서 처리하는 경로로 보낸다
        if (request.isSessionDependent()) {
            return listInMemory(request);
        }
        // 세션이 필요 없는 조건: 거르기·정렬·페이지 자르기를 DB가 처리한다
        List<UserRow> rows = userAccountQueryRepository.findPage(request);
        // 페이지 바깥을 포함한 전체 개수(화면의 페이지 수 계산용)
        long totalElements = userAccountQueryRepository.count(request);
        // 이 페이지 계정들의 세션을 한 번에 읽는다
        AccountActivities activities = accountActivityService.loadActivities(SessionTable.USER, loginIdsOf(rows));
        return UserListAssembler.assemblePage(rows, totalElements, activities, request);
    }

    // DB 조건에 맞는 계정을 상한까지 읽어 세션 정보를 붙이고, 거르기·정렬·페이지 자르기는 UserListAssembler가 메모리에서 한다
    private PageResponse<UserListItem> listInMemory(UserSearchRequest request) {
        // 상한 + 1명까지 읽어 상한을 넘었는지 알 수 있게 한다
        List<UserRow> candidates =
                userAccountQueryRepository.findCandidates(request, UserListAssembler.SESSION_DEPENDENT_LIMIT + 1);
        // 세션 조회는 실제로 다루는(상한까지의) 계정만 대상으로 한다
        List<UserRow> considered =
                candidates.subList(0, Math.min(candidates.size(), UserListAssembler.SESSION_DEPENDENT_LIMIT));
        AccountActivities activities = accountActivityService.loadActivities(SessionTable.USER, loginIdsOf(considered));
        // 상한을 넘었는지 판단할 수 있게 상한+1개까지 읽은 후보 전체를 넘긴다
        return UserListAssembler.assembleInMemory(candidates, activities, request);
    }

    // 세션 조회에 쓸 로그인 아이디만 뽑는다
    private static List<String> loginIdsOf(List<UserRow> rows) {
        return rows.stream().map(UserRow::loginId).toList();
    }
}
