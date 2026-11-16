package com.whiteeyes.user.account.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.whiteeyes.common.account.entity.UserAccount;
import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.common.account.service.AccountPasswordStore;

/** 사용자 앱의 비밀번호 변경(FNC-C-007)이 user_account를 읽고 쓰는 곳. */
@Component
@RequiredArgsConstructor
public class UserAccountPasswordStore implements AccountPasswordStore {

    private final UserAccountRepository userAccountRepository;

    /**
     * 저장된 암호화 비밀번호를 읽는다. 현재 비밀번호 확인에 쓴다.
     *
     * @return 계정이 없으면 빈 값
     */
    @Override
    public Optional<String> findEncodedPassword(Long accountId) {
        return userAccountRepository.findById(accountId).map(UserAccount::getPassword);
    }

    /**
     * 새 암호화 비밀번호로 바꾼다. 계정이 없으면 예외를 던진다(로그인한 사람의 번호라 정상 흐름에선 없을 수 없다).
     */
    @Override
    public void changePassword(Long accountId, String encodedPassword) {
        // save를 부르지 않는다. 엔티티 메서드로 값만 바꾸고 DB 반영은 호출한 쪽 트랜잭션의 변경 감지에 맡긴다.
        userAccountRepository.findById(accountId).orElseThrow().changePassword(encodedPassword);
    }
}
