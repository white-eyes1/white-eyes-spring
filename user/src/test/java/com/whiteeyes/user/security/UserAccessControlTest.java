package com.whiteeyes.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.account.repository.UserAccountRepository;
import com.whiteeyes.user.support.UserTestAccounts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 사용자 앱 접근 권한 검사(FNC-C-003): 권한 표(사용자 앱)의 경로를 비로그인·PENDING·USER로 호출해 허용/거부를 확인한다.
 * 권한 표: 공개 /login·/signup·/api/v1/signup/**, PENDING은 /pending·/logout, USER는 그 외 전부.
 * 역할이 있는 요청은 실제로 로그인(POST /login)한 세션으로 보낸다.
 * 컨트롤러가 아직 없는 경로는 허용되면 404가 나온다. 이 테스트의 '허용'은 401·403·로그인 화면 이동이 아닌 것이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserAccessControlTest {

    private static final String STAT_SERIES =
            "/api/v1/stats/series?targetType=REGION&targetId=999999&interval=1h&from=2099-01-01T10:00&to=2099-01-01T11:00";
    private static final String COMPARE =
            "/api/v1/compare?targetType=ZONE&targetId=1&from=2099-01-05&to=2099-01-11&basePreset=LAST_MONTH";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserTestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new UserTestAccounts(mockMvc, jdbcTemplate, userAccountRepository, passwordEncoder);
        accounts.clear();
    }

    @AfterEach
    void tearDown() {
        accounts.clear();
    }

    private void assertAllowed(MvcResult result) {
        assertThat(result.getResponse().getStatus()).as("허용이어야 하는 요청의 상태").isNotIn(401, 403);
        assertThat(result.getResponse().getRedirectedUrl()).as("로그인·승인 대기 화면으로 이동하지 않아야 함").isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/login", "/signup", "/api/v1/signup/check-id?loginId=newuser01"})
    @DisplayName("FNC-C-003 공개 경로는 로그인 없이 열린다")
    void anonymous_publicPaths_allowed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/css/we.css", "/js/common/api.js", "/images/logo.png", "/favicon.ico"})
    @DisplayName("FNC-C-003 정적 파일 경로는 로그인 없이 통과한다")
    void anonymous_staticFiles_allowed(String path) throws Exception {
        assertAllowed(mockMvc.perform(get(path)).andReturn());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/stats", "/stats/graph", "/stats/compare", "/me/password", "/pending", "/actuator/health"})
    @DisplayName("FNC-C-003 세션 없이 화면을 요청하면 로그인 화면으로 이동한다(권한 표에 없는 /actuator/health 포함)")
    void anonymous_screen_redirectsToLogin(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {"/api/v1/regions", "/api/v1/zones", STAT_SERIES, COMPARE, "/api/v1/meta/levels", "/api/v1/me"})
    @DisplayName("FNC-C-003 세션 없이 사용자 이상 API를 요청하면 공통 오류 형식의 401을 돌려준다")
    void anonymous_api_unauthorized(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/regions", "/api/v1/zones", STAT_SERIES, COMPARE, "/api/v1/meta/levels"})
    @DisplayName("FNC-C-003 로그인한 USER는 사용자 이상 API를 쓸 수 있다")
    void user_api_allowed(String path) throws Exception {
        Cookie session = accounts.loginAs("user01", "ACTIVE", false);

        assertAllowed(mockMvc.perform(get(path).cookie(session)).andReturn());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/regions", "/api/v1/zones", STAT_SERIES, COMPARE, "/api/v1/meta/levels"})
    @DisplayName("FNC-U-003 로그인한 PENDING 계정이 사용자 이상 API를 요청하면 403")
    void pending_api_forbidden(String path) throws Exception {
        Cookie session = accounts.loginAs("wait01", "PENDING", false);

        mockMvc.perform(get(path).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("FNC-C-003 승인 대기 화면은 PENDING 전용이라 USER가 열면 403")
    void user_pendingPage_forbidden() throws Exception {
        Cookie session = accounts.loginAs("user02", "ACTIVE", false);

        mockMvc.perform(get("/pending").cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FNC-C-003 로그인한 USER는 비밀번호 변경 화면을 연다")
    void user_passwordPage_allowed() throws Exception {
        Cookie session = accounts.loginAs("user03", "ACTIVE", false);

        mockMvc.perform(get("/me/password").cookie(session)).andExpect(status().isOk());
    }
}
