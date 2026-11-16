package com.whiteeyes.user.account.dto;

/**
 * 아이디 중복 확인 응답(FNC-U-001).
 *
 * @param available true면 아직 쓰는 사람이 없어 가입에 쓸 수 있다
 */
public record LoginIdCheckResponse(boolean available) {}
