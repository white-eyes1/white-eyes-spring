package com.whiteeyes.admin.account.repository;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    boolean existsByRole(AdminRole role);

    Optional<AdminAccount> findByLoginId(String loginId);

    // 비활성화한 계정도 지우지 않으므로 상태와 관계없이 본다
    boolean existsByLoginId(String loginId);

    /**
     * 상태를 바꾸기 전에 행을 잠그고 읽는다(FNC-A-011). 최고 관리자 두 명이 같은 계정을 동시에 바꾸면
     * 늦은 쪽은 앞의 커밋을 기다렸다가 바뀐 상태를 보고 409로 거부된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AdminAccount a WHERE a.id = :id")
    Optional<AdminAccount> findByIdForUpdate(@Param("id") Long id);
}
