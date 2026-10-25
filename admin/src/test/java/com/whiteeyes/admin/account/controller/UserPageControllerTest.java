package com.whiteeyes.admin.account.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 사용자 관리 화면 틀(SCR-A-001, FNC-A-002). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminTestUserConfig.class)
class UserPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("SCR-A-001 사용자 관리 화면에 검색 영역·정렬 머리글·안내 영역이 있다")
    void users_rendersSkeleton() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(content().string(containsString("id=\"user-search-form\"")))
                .andExpect(content().string(containsString("data-sort=\"loginId\"")))
                .andExpect(content().string(containsString("data-sort=\"createdAt\"")))
                .andExpect(content().string(containsString("data-sort=\"lastActiveAt\"")))
                .andExpect(content().string(containsString("id=\"user-list-truncated\"")))
                .andExpect(content().string(containsString("id=\"user-list-error\"")))
                .andExpect(content().string(containsString("id=\"user-pagination\"")));
    }

    @Test
    @DisplayName("SCR-A-001 강제 로그아웃 확인 창이 있다(FNC-A-008)")
    void forceLogoutConfirmModal() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(content().string(containsString("id=\"user-confirm-modal\" hidden")))
                .andExpect(content().string(containsString("id=\"user-confirm-ok\"")));
    }

    // 상세 창은 user-detail.js가 행 클릭으로 연다. 임시 비밀번호 상자는 재설정 응답을 받은 뒤에만 채워진다(FNC-A-005)
    @Test
    @DisplayName("SCR-A-002 사용자 상세 창에 재설정 버튼·확인 창·임시 비밀번호 상자가 있고 처음에는 숨겨져 있다")
    void users_hasDetailModalWithPasswordReset() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(content().string(containsString("id=\"user-detail-modal\" hidden")))
                .andExpect(content().string(containsString("id=\"user-detail-reset\"")))
                .andExpect(content().string(containsString("id=\"user-reset-confirm-modal\" hidden")))
                .andExpect(content().string(containsString("id=\"user-reset-confirm-ok\"")))
                .andExpect(content().string(containsString("id=\"user-temp-password-box\" hidden")))
                .andExpect(content().string(containsString("id=\"user-temp-password\"")))
                .andExpect(content().string(containsString("id=\"user-temp-copy\"")))
                .andExpect(content().string(containsString("이 창을 닫으면 다시 볼 수 없습니다")));
    }

    // 저장은 user-detail.js가 폼 제출로 PATCH /api/v1/users/{id}를 부른다(FNC-A-004)
    @Test
    @DisplayName("SCR-A-002 사용자 상세 창의 이름·메일·연락처는 고칠 수 있고, 칸마다 오류 자리와 취소·저장 버튼이 있다")
    void users_hasEditableDetailForm() throws Exception {
        String[] expected = {
            "id=\"user-detail-form\"", "id=\"user-detail-error\"",
            "id=\"user-detail-name\" name=\"name\"", "data-error-for=\"name\"",
            "id=\"user-detail-email\" name=\"email\"", "data-error-for=\"email\"",
            "id=\"user-detail-phone\" name=\"phone\"", "data-error-for=\"phone\"",
            ">취소</button>", "type=\"submit\" id=\"user-detail-save\">저장</button>"
        };
        var result = mockMvc.perform(get("/users"));
        for (String fragment : expected) {
            result.andExpect(content().string(containsString(fragment)));
        }
        // 아이디는 고칠 칸이 없다(업무 규칙 '아이디·역할은 바꿀 수 없음'). 이름·메일·연락처 칸은 읽기 전용이 아니다
        result.andExpect(content().string(not(containsString("id=\"user-detail-login-id\""))))
                .andExpect(content().string(not(containsString("readonly"))));
    }

    // 행의 승인 버튼(FNC-A-006)은 users.js가 이 열에 그린다
    @Test
    @DisplayName("SCR-A-001 사용자 표에 동작 열이 있다")
    void users_hasActionsColumn() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(content().string(containsString("<th scope=\"col\" class=\"user-actions\">동작</th>")));
    }

    // common/api.js가 이 두 meta로 컨텍스트 경로(/admin)와 CSRF 토큰을 읽는다
    @Test
    @DisplayName("SCR-A-001 사용자 관리 화면은 목록 JS와 api.js가 읽을 meta를 갖는다")
    void users_linksScriptAndMeta() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(content().string(containsString("src=\"/js/admin/pages/users.js\"")))
                .andExpect(content().string(containsString("<meta name=\"context-path\" content=\"/\">")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\" content=\"")));
    }

    @Test
    @DisplayName("SCR-A-001 사용자 관리 화면은 공통 화면 틀(head·관리자 헤더·scripts) 위에 붙는다")
    void users_usesCommonLayout() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(content().string(containsString("<title>사용자 관리 - White Eyes</title>")))
                .andExpect(content().string(containsString("href=\"/css/we.css\"")))
                .andExpect(content().string(containsString("href=\"/css/admin/users.css\"")))
                .andExpect(content().string(containsString("data-header=\"admin\"")))
                .andExpect(content().string(containsString("src=\"/js/common/header.js\"")));
    }

    // users.js·users.css는 admin 모듈, 나머지는 common 모듈에 있어 모두 관리자 앱에서 내려가는지 본다
    @ParameterizedTest
    @ValueSource(strings = {
        "/js/admin/pages/users.js",
        "/js/admin/user-detail.js",
        "/js/common/api.js",
        "/js/common/format.js",
        "/js/common/toast.js",
        "/css/admin/users.css"
    })
    @DisplayName("SCR-A-001 화면이 쓰는 JS·CSS 파일을 관리자 앱이 내려준다")
    void users_scriptsServed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
