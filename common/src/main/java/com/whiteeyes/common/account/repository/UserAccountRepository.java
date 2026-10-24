package com.whiteeyes.common.account.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.account.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** 상태와 관계없이 같은 login_id가 있는지 본다. 탈퇴 계정도 있음으로 센다(auth-005). */
    boolean existsByLoginId(String loginId);

    /** 로그인할 때 아이디로 계정을 찾는다(FNC-C-001 처리 순서 1). 상태는 부르는 쪽이 본다. */
    Optional<UserAccount> findByLoginId(String loginId);

    /**
     * 상태를 바꾸기 전에 행을 잠그고 읽는다. 관리자 두 명이 같은 계정을 동시에 바꾸면
     * 늦은 쪽은 앞의 커밋을 기다렸다가 바뀐 상태를 보고 거부된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserAccount u WHERE u.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") Long id);
}
