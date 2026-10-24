package com.whiteeyes.user.account.service;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.account.service.AccountProfile;
import com.whiteeyes.common.account.service.AccountProfileStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 사용자 앱의 내 정보 조회·수정(FNC-C-006)이 user_account를 읽고 쓰는 곳. 사용자 계정에는 역할이 없다. */
@Component
public class UserAccountProfileStore implements AccountProfileStore {

    private final UserAccountRepository userAccountRepository;

    public UserAccountProfileStore(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public Optional<AccountProfile> findProfile(Long accountId) {
        return userAccountRepository
                .findById(accountId)
                .map(account -> new AccountProfile(
                        account.getId(),
                        account.getLoginId(),
                        account.getName(),
                        account.getEmail(),
                        account.getPhone(),
                        account.getStatus().name(),
                        null,
                        account.isMustChangePassword()));
    }

    @Override
    public void updateProfile(Long accountId, String name, String email, String phone) {
        userAccountRepository.findById(accountId).orElseThrow().updateProfile(name, email, phone);
    }
}
