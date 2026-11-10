package com.whiteeyes.user.account.dto;

import com.whiteeyes.common.account.entity.UserAccountStatus;

public record SignupResponse(Long id, UserAccountStatus status) {}
