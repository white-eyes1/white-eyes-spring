package com.whiteeyes.admin.account.dto;

/**
 * 비밀번호 재설정 결과(FNC-A-005 정상 결과).
 *
 * @param temporaryPassword 임시 비밀번호. 이 응답에서 한 번만 보여 주고 서버에는 bcrypt 값만 남는다(conventions.md '임시 비밀번호')
 */
// 사용자·관리자 비밀번호 재설정이 함께 쓰는 응답이다. 평문 임시 비밀번호는 이 응답에서만 볼 수 있다
public record PasswordResetResponse(String temporaryPassword) {

    /** 웹 디버그 로그 등에서 임시 비밀번호가 문자열로 새지 않게 가린다. 응답 JSON에는 영향이 없다. */
    @Override
    public String toString() {
        return "PasswordResetResponse[temporaryPassword=***]";
    }
}
