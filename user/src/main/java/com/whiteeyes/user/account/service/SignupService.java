package com.whiteeyes.user.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.account.repository.UserAccountRepository;

@Service
public class SignupService {

    private final UserAccountRepository userAccountRepository;

    public SignupService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    // 탈퇴 계정의 아이디도 사용 불가라서 상태를 보지 않는다(FNC-U-001, auth-005)
    @Transactional(readOnly = true)
    public boolean isLoginIdAvailable(String loginId) {
        return !userAccountRepository.existsByLoginId(loginId);
    }
}
