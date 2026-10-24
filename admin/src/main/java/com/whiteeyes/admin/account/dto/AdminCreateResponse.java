package com.whiteeyes.admin.account.dto;

/**
 * 관리자 계정 생성 결과(FNC-A-009 정상 결과).
 *
 * @param temporaryPassword 임시 비밀번호. 이 응답에서 한 번만 보여 주고 서버에는 bcrypt 값만 남는다(conventions.md '임시 비밀번호')
 */
public record AdminCreateResponse(Long id, String temporaryPassword) {

    /** 웹 디버그 로그 등에서 임시 비밀번호가 문자열로 새지 않게 가린다. 응답 JSON에는 영향이 없다. */
    @Override
    public String toString() {
        return "AdminCreateResponse[id=" + id + ", temporaryPassword=***]";
    }
}
