package com.whiteeyes.common.account.service;

import java.util.Optional;

/**
 * 내 정보 조회·수정(FNC-C-006)이 계정 테이블을 읽고 쓰는 입구. 사용자 앱은 user_account, 관리자 앱은 admin_account를 쓴다.
 * admin_account 엔티티는 admin 모듈에만 있어(package-structure.md 4장) common이 직접 부를 수 없으므로 각 앱이 구현한다.
 */
public interface AccountProfileStore {

    /** 계정 번호로 내 정보를 찾는다. 계정이 없으면 빈 값. */
    Optional<AccountProfile> findProfile(Long accountId);

    /** 이름·메일·연락처를 저장한다. 메일·연락처가 null이면 비운다. */
    void updateProfile(Long accountId, String name, String email, String phone);
}
