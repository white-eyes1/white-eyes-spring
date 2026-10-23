package com.whiteeyes.user.account.service;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.account.service.AccountPasswordStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 사용자 앱의 비밀번호 변경(FNC-C-007)이 user_account를 읽고 쓰는 곳. */
@Component
public class UserAccountPasswordStore implements AccountPasswordStore {

    private final UserAccountRepository userAccountRepository;

    public UserAccountPasswordStore(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public Optional<String> findEncodedPassword(Long accountId) {
        return userAccountRepository.findById(accountId).map(UserAccount::getPassword);
    }

    @Override
    public void changePassword(Long accountId, String encodedPassword) {
        userAccountRepository.findById(accountId).orElseThrow().changePassword(encodedPassword);
    }
}
