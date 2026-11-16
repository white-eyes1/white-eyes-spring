package com.whiteeyes.user.account.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.account.service.AccountProfile;
import com.whiteeyes.common.account.service.AccountProfileStore;

/** 사용자 앱의 내 정보 조회·수정(FNC-C-006)이 user_account를 읽고 쓰는 곳. 사용자 계정에는 역할이 없다. */
@Component
@RequiredArgsConstructor
public class UserAccountProfileStore implements AccountProfileStore {

    private final UserAccountRepository userAccountRepository;

    /**
     * 내 정보 화면에 보여줄 값을 읽는다.
     *
     * @return 계정이 없으면 빈 값
     */
    @Override
    public Optional<AccountProfile> findProfile(Long accountId) {
        return userAccountRepository
                .findById(accountId)
                // 엔티티를 관리자·사용자 공용 AccountProfile로 바꾼다. 인자 순서는 AccountProfile 정의를 따르고, 끝에서 두 번째(역할)는 사용자에게 없어 null이다.
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

    /**
     * 이름·이메일·전화번호를 바꾼다. 계정이 없으면 예외를 던진다.
     */
    @Override
    public void updateProfile(Long accountId, String name, String email, String phone) {
        // save 없이 엔티티 값만 바꾼다. 반영은 호출한 쪽 트랜잭션의 변경 감지에 맡긴다.
        userAccountRepository.findById(accountId).orElseThrow().updateProfile(name, email, phone);
    }
}
