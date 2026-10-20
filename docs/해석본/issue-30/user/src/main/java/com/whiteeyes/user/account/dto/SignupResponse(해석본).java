/*
 * [해석본] 원본: user/src/main/java/com/whiteeyes/user/account/dto/SignupResponse.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002), API: POST /api/v1/signup (public.md), 화면: SCR-U-001
 *
 * [무엇] 가입 성공 응답 {"id": 21, "status": "PENDING"}(public.md).
 * [왜 DTO인가] 엔티티를 controller 밖으로 내보내지 않는다(package-structure 3-3). 비밀번호 같은 칼럼이 새지 않는다.
 */
package com.whiteeyes.user.account.dto;

import com.whiteeyes.common.account.entity.UserAccountStatus;

// [해석] status를 enum으로 두면 Jackson이 이름 문자열("PENDING")로 쓴다.
public record SignupResponse(Long id, UserAccountStatus status) {}
