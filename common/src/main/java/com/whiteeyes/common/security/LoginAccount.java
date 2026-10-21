package com.whiteeyes.common.security;

/**
 * 지금 요청을 보낸 로그인 계정. 작업 기록(FNC-C-026)의 행동한 계정 칸을 채우는 데 쓴다.
 *
 * @param accountId 계정 번호(admin_account.id 또는 user_account.id)
 * @param loginId 로그인 아이디
 */
public record LoginAccount(Long accountId, String loginId) {}
