package com.whiteeyes.user.account.dto;

import com.whiteeyes.common.account.entity.UserAccountStatus;

/**
 * 회원가입 응답(FNC-U-002).
 *
 * @param id 만들어진 계정 번호
 * @param status 가입 직후 상태. 승인 전이라 PENDING이다
 */
public record SignupResponse(Long id, UserAccountStatus status) {}
