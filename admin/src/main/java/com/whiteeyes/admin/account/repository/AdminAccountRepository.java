package com.whiteeyes.admin.account.repository;

import com.whiteeyes.admin.account.entity.AdminAccount;
import com.whiteeyes.admin.account.entity.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    boolean existsByRole(AdminRole role);
}
