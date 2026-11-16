package com.whiteeyes.admin.account.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;

/** 관리자 계정(admin_account)을 읽고 쓰는 Spring Data JPA 저장소. 메서드 이름이 곧 쿼리가 된다(구현 클래스는 스프링이 만든다). */
public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    // 해당 역할의 계정이 하나라도 있는지. 앱 시작 때 최고 관리자가 이미 있는지 확인하는 데 쓴다(SuperAdminInitializer)
    boolean existsByRole(AdminRole role);

    // 로그인 아이디로 한 건 조회(로그인 처리·로그인 시각 기록). 없으면 빈 Optional
    Optional<AdminAccount> findByLoginId(String loginId);

    // 비활성화한 계정도 지우지 않으므로 상태와 관계없이 본다
    boolean existsByLoginId(String loginId);

    /**
     * 상태를 바꾸기 전에 행을 잠그고 읽는다(FNC-A-011). 최고 관리자 두 명이 같은 계정을 동시에 바꾸면
     * 늦은 쪽은 앞의 커밋을 기다렸다가 바뀐 상태를 보고 409로 거부된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // 쿼리 자체는 번호로 한 건 읽는 JPQL이고, 위 @Lock 때문에 SELECT ... FOR UPDATE로 실행되어 다른 트랜잭션이 이 행을 바꾸려면 기다린다
    @Query("SELECT a FROM AdminAccount a WHERE a.id = :id")
    Optional<AdminAccount> findByIdForUpdate(@Param("id") Long id);
}
