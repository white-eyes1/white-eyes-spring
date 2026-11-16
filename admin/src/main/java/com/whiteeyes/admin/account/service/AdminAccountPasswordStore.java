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

    // 관리자 계정 저장소. 사용자 앱은 같은 인터페이스(AccountPasswordStore)를 자기 테이블로 구현한다
    private final AdminAccountRepository adminAccountRepository;

    /** 계정의 bcrypt로 암호화된 현재 비밀번호를 돌려준다. 계정이 없으면 빈 값이다. */
    @Override
    /** 계정의 bcrypt로 암호화된 현재 비밀번호를 돌려준다. 계정이 없으면 빈 값이다. */
    public Optional<String> findEncodedPassword(Long accountId) {
        return adminAccountRepository.findById(accountId).map(AdminAccount::getPassword);
    }

    /** 암호화된 새 비밀번호로 바꾼다. 엔티티의 changePassword가 변경 강제 표시도 함께 끈다. */
    @Override
    /** 암호화된 새 비밀번호로 바꾼다. 엔티티의 changePassword가 변경 강제 표시도 함께 끈다. */
    public void changePassword(Long accountId, String encodedPassword) {
        // 로그인한 본인의 계정이라 항상 있어야 하므로 없으면 예외다. 값만 바꾸면 트랜잭션 종료 때 JPA가 UPDATE한다
        adminAccountRepository.findById(accountId).orElseThrow().changePassword(encodedPassword);
    }
}
