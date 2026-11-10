package com.whiteeyes.admin.account.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.common.account.service.AccountPasswordStore;

/** 관리자 앱의 비밀번호 변경(FNC-C-007)이 admin_account를 읽고 쓰는 곳. */
@Component
@RequiredArgsConstructor
public class AdminAccountPasswordStore implements AccountPasswordStore {

    private final AdminAccountRepository adminAccountRepository;

    @Override
    public Optional<String> findEncodedPassword(Long accountId) {
        return adminAccountRepository.findById(accountId).map(AdminAccount::getPassword);
    }

    @Override
    public void changePassword(Long accountId, String encodedPassword) {
        adminAccountRepository.findById(accountId).orElseThrow().changePassword(encodedPassword);
    }
}
