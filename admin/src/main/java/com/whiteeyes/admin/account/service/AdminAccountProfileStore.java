package com.whiteeyes.admin.account.service;

import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.common.account.service.AccountProfile;
import com.whiteeyes.common.account.service.AccountProfileStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 관리자 앱의 내 정보 조회·수정(FNC-C-006)이 admin_account를 읽고 쓰는 곳. */
@Component
public class AdminAccountProfileStore implements AccountProfileStore {

    private final AdminAccountRepository adminAccountRepository;

    public AdminAccountProfileStore(AdminAccountRepository adminAccountRepository) {
        this.adminAccountRepository = adminAccountRepository;
    }

    @Override
    public Optional<AccountProfile> findProfile(Long accountId) {
        return adminAccountRepository
                .findById(accountId)
                .map(account -> new AccountProfile(
                        account.getId(),
                        account.getLoginId(),
                        account.getName(),
                        account.getEmail(),
                        account.getPhone(),
                        account.getStatus().name(),
                        account.getRole().name(),
                        account.isMustChangePassword()));
    }

    @Override
    public void updateProfile(Long accountId, String name, String email, String phone) {
        adminAccountRepository.findById(accountId).orElseThrow().updateProfile(name, email, phone);
    }
}
