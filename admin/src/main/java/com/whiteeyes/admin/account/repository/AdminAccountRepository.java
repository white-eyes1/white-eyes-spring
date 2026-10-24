package com.whiteeyes.admin.account.repository;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    boolean existsByRole(AdminRole role);

    Optional<AdminAccount> findByLoginId(String loginId);

    // 비활성화한 계정도 지우지 않으므로 상태와 관계없이 본다
    boolean existsByLoginId(String loginId);
}
