package com.whiteeyes.user.account.dto;

import jakarta.validation.constraints.NotBlank;

/** 회원 탈퇴 요청(DELETE /api/v1/me, FNC-U-004). 본인 확인용 현재 비밀번호다. */
public record WithdrawRequest(@NotBlank(message = "비밀번호를 입력해 주세요.") String password) {}
