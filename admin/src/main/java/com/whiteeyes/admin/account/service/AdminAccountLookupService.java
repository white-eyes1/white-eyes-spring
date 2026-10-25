package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인(이벤트 엑셀 등)이 관리자 계정을 조회할 때 부르는 계정 도메인의 공개 service. */
@Service
@RequiredArgsConstructor
public class AdminAccountLookupService {

    private final AdminAccountRepository adminAccountRepository;

    /** 관리자 번호 → 로그인 아이디. 없는 번호는 결과에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, String> findLoginIds(Collection<Long> adminIds) {
        if (adminIds.isEmpty()) {
            return Map.of();
        }
        return adminAccountRepository.findAllById(adminIds).stream()
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getLoginId, (first, second) -> first));
    }
}
