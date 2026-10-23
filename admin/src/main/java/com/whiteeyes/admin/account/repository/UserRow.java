package com.whiteeyes.admin.account.repository;

import com.whiteeyes.common.account.entity.UserAccountStatus;
import java.time.LocalDateTime;

/**
 * 사용자 목록 한 줄에 필요한 user_account 칼럼(tables.md 'user_account'). 비밀번호처럼 목록에 쓰지 않는 칼럼은 담지 않는다.
 *
 * @param createdAt 가입 시각(KST)
 * @param lastLoginAt 마지막 로그인 시각(KST). 로그인한 적이 없으면 null
 */
public record UserRow(
        Long id,
        String loginId,
        String name,
        String email,
        String phone,
        UserAccountStatus status,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt) {}
