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
 * 강제 로그아웃(FNC-A-008) 검수 기준 중 사용자 앱 쪽: 관리자 앱이 사용자의 세션을 세션 표에서 지우면 그 브라우저는
 * 다음 요청에서 로그인 화면으로 이동한다. 지우는 쪽(관리자 앱)은 admin의 ForceLogoutTest가 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserForceLogoutEffectTest {

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
    @DisplayName("FNC-A-008 세션 표에서 지운 사용자의 다음 요청은 로그인 화면으로, API는 401")
    void deletedSession_nextRequest_redirectsToLogin() throws Exception {
        Cookie session = accounts().loginAs("forced01", "ACTIVE", false);
        mockMvc.perform(get("/me/password").cookie(session)).andExpect(status().isOk());

        deleteSessionsOf("forced01");

        mockMvc.perform(get("/me/password").cookie(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/api/v1/meta/levels").cookie(session)).andExpect(status().isUnauthorized());
    }

    // 관리자 앱이 하는 일과 같다: 로그인 아이디(PRINCIPAL_NAME)로 찾아 모두 지운다(conventions.md '세션 주인 이름')
    private void deleteSessionsOf(String loginId) {
        var sessions = sessionRepository.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, loginId);
        assertThat(sessions).as("지우기 전 세션").isNotEmpty();
        sessions.keySet().forEach(sessionRepository::deleteById);
    }
}
