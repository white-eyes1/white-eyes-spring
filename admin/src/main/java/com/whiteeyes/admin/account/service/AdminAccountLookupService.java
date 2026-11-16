package com.whiteeyes.admin.account.service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;

/** 다른 도메인(이벤트 엑셀 등)이 관리자 계정을 조회할 때 부르는 계정 도메인의 공개 service(FNC-A-039 처리자 아이디). */
@Service
@RequiredArgsConstructor
public class AdminAccountLookupService {

    private final AdminAccountRepository adminAccountRepository;

    /** 관리자 번호 → 로그인 아이디. 없는 번호는 결과에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, String> findLoginIds(Collection<Long> adminIds) {
        // 빈 목록이면 DB를 조회하지 않는다
        if (adminIds.isEmpty()) {
            return Map.of();
        }
        // 번호 목록을 한 번에 읽어 번호 -> 아이디 맵으로 만든다
        return adminAccountRepository.findAllById(adminIds).stream()
                // 같은 번호가 겹치면 앞의 값을 쓴다(겹침 시 toMap이 예외를 던지지 않게 하는 보호 장치)
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getLoginId, (first, second) -> first));
    }
}
