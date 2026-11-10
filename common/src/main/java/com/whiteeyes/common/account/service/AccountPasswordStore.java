package com.whiteeyes.common.account.service;

import java.util.Optional;

/**
 * 비밀번호 변경(FNC-C-007)이 계정 테이블을 읽고 쓰는 입구. 두 앱이 같은 경로로 비밀번호를 바꾸지만
 * 사용자 앱은 user_account, 관리자 앱은 admin_account를 쓴다. admin_account 엔티티는 admin 모듈에만 있어
 * (package-structure.md 4장) common이 직접 부를 수 없으므로, 각 앱이 이 인터페이스를 구현한다.
 */
public interface AccountPasswordStore {

    /** 계정 번호로 저장된 비밀번호 해시를 찾는다. 계정이 없으면 빈 값. */
    Optional<String> findEncodedPassword(Long accountId);

    /** 새 비밀번호 해시를 저장하고 must_change_password를 끈다. */
    void changePassword(Long accountId, String encodedPassword);
}
