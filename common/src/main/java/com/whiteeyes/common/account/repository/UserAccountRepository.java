package com.whiteeyes.common.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.account.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** 상태와 관계없이 같은 login_id가 있는지 본다. 탈퇴 계정도 있음으로 센다(auth-005). */
    boolean existsByLoginId(String loginId);
}
