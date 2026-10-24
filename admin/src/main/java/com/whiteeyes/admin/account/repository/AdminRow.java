package com.whiteeyes.admin.account.repository;

import com.whiteeyes.admin.account.entity.AdminRole;
import com.whiteeyes.admin.account.entity.AdminStatus;
import java.time.LocalDateTime;

/**
 * 관리자 목록 한 줄에 필요한 admin_account 칼럼(tables.md 'admin_account'). 비밀번호는 담지 않는다.
 *
 * @param createdAt 계정 생성 시각(KST)
 * @param lastLoginAt 마지막 로그인 시각(KST). 로그인한 적이 없으면 null
 */
public record AdminRow(
        Long id,
        String loginId,
        String name,
        String email,
        String phone,
        AdminRole role,
        AdminStatus status,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt) {}
