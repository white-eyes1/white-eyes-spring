package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.support.UserTestAccounts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 사용자 승인 취소(FNC-A-007) 검수 기준 중 사용자 앱 쪽: 관리자 앱이 상태를 PENDING으로 바꾸고 세션을 지우면
 * 그 브라우저는 다음 요청에서 로그인 화면으로 가고, 다시 로그인하면 승인 대기 화면으로 간다(FNC-U-003).
 * 바꾸고 지우는 쪽(관리자 앱)은 admin의 UserUnapproveTest가 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserUnapproveEffectTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserTestAccounts accounts() {
        return new UserTestAccounts(mockMvc, jdbcTemplate, userAccountRepository, passwordEncoder);
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        accounts().clear();
    }

    @Test
    @DisplayName("FNC-A-007 승인 취소 직후 대상 브라우저의 다음 요청은 로그인 화면으로, 다시 로그인하면 승인 대기 화면으로")
    void unapproved_nextRequest_redirectsToLogin() throws Exception {
        Cookie session = accounts().loginAs("revoked01", "ACTIVE", false);
        mockMvc.perform(get("/stats").cookie(session)).andExpect(status().isOk());

        // 관리자 앱의 승인 취소가 하는 일과 같다: 상태를 PENDING으로 바꾸고 로그인 아이디로 세션을 모두 지운다
        jdbcTemplate.update("UPDATE user_account SET status = 'PENDING' WHERE login_id = ?", "revoked01");
        deleteSessionsOf("revoked01");

        mockMvc.perform(get("/stats").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/api/v1/meta/levels").cookie(session)).andExpect(status().isUnauthorized());

        var relogin = accounts().login("revoked01", UserTestAccounts.PASSWORD);
        assertThat(relogin.getResponse().getRedirectedUrl()).isEqualTo("/pending");
    }

    private void deleteSessionsOf(String loginId) {
        var sessions = sessionRepository.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, loginId);
        assertThat(sessions).as("지우기 전 세션").isNotEmpty();
        sessions.keySet().forEach(sessionRepository::deleteById);
    }
}
