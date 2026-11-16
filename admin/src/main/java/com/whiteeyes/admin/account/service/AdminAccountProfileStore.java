package com.whiteeyes.admin.account.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.whiteeyes.admin.account.repository.AdminAccountRepository;
import com.whiteeyes.common.account.service.AccountProfile;
import com.whiteeyes.common.account.service.AccountProfileStore;

/** 관리자 앱의 내 정보 조회·수정(FNC-C-006)이 admin_account를 읽고 쓰는 곳. */
@Component
@RequiredArgsConstructor
public class AdminAccountProfileStore implements AccountProfileStore {

    // 관리자 계정 저장소. 사용자 앱은 같은 인터페이스(AccountProfileStore)를 자기 테이블로 구현한다
    private final AdminAccountRepository adminAccountRepository;

    /** 계정을 공용 내 정보 형태(AccountProfile)로 바꿔 돌려준다. 계정이 없으면 빈 값이다. */
    @Override
    /** 계정을 공용 내 정보 형태(AccountProfile)로 바꿔 돌려준다. 계정이 없으면 빈 값이다. */
    public Optional<AccountProfile> findProfile(Long accountId) {
        return adminAccountRepository
                .findById(accountId)
                .map(account -> new AccountProfile(
                        account.getId(),
                        account.getLoginId(),
                        account.getName(),
                        account.getEmail(),
                        account.getPhone(),
                        // 상태·역할은 enum이 아니라 문자열로 넘긴다(사용자 앱과 같은 공용 형태를 쓰기 위함)
                        account.getStatus().name(),
                        account.getRole().name(),
                        account.isMustChangePassword()));
    }

    /** 이름·메일·연락처를 바꾼다. 아이디·역할·상태는 이 경로로 바꿀 수 없다. */
    @Override
    /** 이름·메일·연락처를 바꾼다. 아이디·역할·상태는 이 경로로 바꿀 수 없다. */
    public void updateProfile(Long accountId, String name, String email, String phone) {
        // 로그인한 본인의 계정이라 항상 있어야 하므로 없으면 예외다. 값만 바꾸면 트랜잭션 종료 때 JPA가 UPDATE한다
        adminAccountRepository.findById(accountId).orElseThrow().updateProfile(name, email, phone);
    }
}
