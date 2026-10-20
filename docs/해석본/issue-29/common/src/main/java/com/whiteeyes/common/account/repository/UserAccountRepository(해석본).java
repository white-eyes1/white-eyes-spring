/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/account/repository/UserAccountRepository.java (feature/29-check-login-id 브랜치)
 * 관련 이슈: #29(FNC-U-001)
 *
 * [무엇] user_account를 읽고 쓰는 Spring Data JPA 리포지토리.
 * [왜 common인가] 엔티티와 같은 도메인(account)에 둔다. 공유 엔티티의 리포지토리는 common에 있다(package-structure 3-1).
 *   user 모듈의 SignupService는 같은 account 도메인이라 이 리포지토리를 직접 쓴다
 *   (3-2 '같은 도메인이면 모듈이 달라도 그 도메인의 repository를 쓸 수 있다').
 */
package com.whiteeyes.common.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.account.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    // [해석] 메서드 이름만으로 쿼리가 만들어진다: user_account에서 login_id = ? 인 행이 있으면 true.
    //        일부러 status 조건(예: existsByLoginIdAndStatusNot)을 붙이지 않았다. 탈퇴(WITHDRAWN) 계정의 아이디도
    //        '사용 중'으로 봐야 하기 때문이다(FNC-U-001 처리 순서 2 '탈퇴 계정 포함', auth-005). 이번 완료 기준의 핵심이다.
    //        findByLoginId 대신 exists를 쓴 이유: 필요한 건 있음/없음뿐이라 엔티티를 읽어 올 필요가 없다.
    /** 상태와 관계없이 같은 login_id가 있는지 본다. 탈퇴 계정도 있음으로 센다(auth-005). */
    boolean existsByLoginId(String loginId);
}
