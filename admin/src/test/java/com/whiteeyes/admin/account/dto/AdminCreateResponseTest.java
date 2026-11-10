package com.whiteeyes.admin.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관리자 계정 생성 결과(FNC-A-009 정상 결과) */
class AdminCreateResponseTest {

    @Test
    @DisplayName("FNC-A-009 응답을 문자열로 바꿔도 임시 비밀번호 값이 나오지 않는다")
    void toString_hidesTemporaryPassword() {
        String text = new AdminCreateResponse(12L, "Abcdef123456").toString();

        assertThat(text).doesNotContain("Abcdef123456").contains("id=12", "temporaryPassword=***");
    }
}
