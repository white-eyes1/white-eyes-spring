package com.whiteeyes.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.security.AccountPrincipal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 관리자 앱 접근 권한 검사(FNC-C-003): 권한 표의 각 경로를 역할별로 호출해 허용/거부를 확인한다.
 * 관리자 앱은 컨텍스트 경로가 /admin이라 요청에 같은 경로를 붙여 보낸다.
 * 컨트롤러가 아직 없는 경로는 허용되면 404가 나온다. 이 테스트의 '허용'은 401·403·로그인 화면 이동이 아닌 것이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String LOGIN_REDIRECT = "/admin/login";
    private static final String PASSWORD_CHANGE_REDIRECT = "/admin/me/password";

    // 권한 표(관리자 앱): ADMIN 이상이 쓰는 경로 / SUPER_ADMIN만 쓰는 경로
    private static final List<String> ADMIN_SCREENS = List.of("/stats", "/cameras", "/me", "/events", "/administrators");
    private static final List<String> ADMIN_APIS =
            List.of("/api/v1/manage/zones/1", "/api/v1/events", "/api/v1/me", "/api/v1/users", "/api/v1/administrators");
    private static final List<String> SUPER_SCREENS = List.of("/admins", "/admins/1", "/histories", "/histories/1");
    private static final List<String> SUPER_APIS = List.of(
            "/api/v1/admins", "/api/v1/admins/1", "/api/v1/admins/1/disable", "/api/v1/histories");
    private static final List<String> STATIC_FILES = List.of("/css/app.css", "/js/app.js", "/images/logo.png", "/favicon.ico");

    @Autowired
    private MockMvc mockMvc;

    private MockHttpServletRequestBuilder request(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private MockHttpServletRequestBuilder get(String path) {
        return request(HttpMethod.GET, path);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
        return user("admin01").roles("ADMIN");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asSuperAdmin() {
        return user("super01").roles("SUPER_ADMIN");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asMustChangePassword() {
        return user(new AccountPrincipal(
                "admin02", "encoded", true, true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void assertAllowed(MvcResult result) {
        int status = result.getResponse().getStatus();
        assertThat(status).as("허용이어야 하는 요청의 상태").isNotIn(401, 403);
        assertThat(result.getResponse().getRedirectedUrl()).as("로그인·변경 화면으로 이동하지 않아야 함").isNull();
    }

    static Stream<String> adminPaths() {
        return Stream.concat(ADMIN_SCREENS.stream(), ADMIN_APIS.stream());
    }

    static Stream<String> superPaths() {
        return Stream.concat(SUPER_SCREENS.stream(), SUPER_APIS.stream());
    }

    @Test
    @DisplayName("FNC-C-003 공개 경로 /login은 로그인 없이 열린다")
    void anonymous_loginPage_allowed() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.whiteeyes.admin.security.AccessControlTest#staticFiles")
    @DisplayName("FNC-C-003 정적 파일 경로는 로그인 없이 통과한다")
    void anonymous_staticFiles_allowed(String path) throws Exception {
        assertAllowed(mockMvc.perform(get(path)).andReturn());
    }

    static Stream<String> staticFiles() {
        return STATIC_FILES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/stats", "/admins", "/histories/1", "/cameras"})
    @DisplayName("FNC-C-003 세션 없이 화면을 요청하면 로그인 화면으로 이동한다")
    void anonymous_screen_redirectsToLogin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/manage/zones/1", "/api/v1/admins", "/api/v1/histories", "/api/v1/me"})
    @DisplayName("FNC-C-003 세션 없이 API를 요청하면 공통 오류 형식의 401을 돌려준다")
    void anonymous_api_unauthorized(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminPaths")
    @DisplayName("FNC-C-003 ADMIN은 ADMIN 이상 경로를 쓸 수 있다")
    void admin_adminPaths_allowed(String path) throws Exception {
        assertAllowed(mockMvc.perform(get(path).with(asAdmin())).andReturn());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("superPaths")
    @DisplayName("FNC-C-003 SUPER_ADMIN은 ADMIN 이상 경로와 최고 관리자 전용 경로를 모두 쓸 수 있다")
    void superAdmin_allPaths_allowed(String path) throws Exception {
        assertAllowed(mockMvc.perform(get(path).with(asSuperAdmin())).andReturn());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminPaths")
    @DisplayName("FNC-C-003 SUPER_ADMIN은 ADMIN 이상 경로도 쓸 수 있다")
    void superAdmin_adminPaths_allowed(String path) throws Exception {
        assertAllowed(mockMvc.perform(get(path).with(asSuperAdmin())).andReturn());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/admins", "/api/v1/admins/1", "/api/v1/admins/1/disable", "/api/v1/histories"})
    @DisplayName("FNC-C-003 ADMIN이 최고 관리자 전용 API를 부르면 공통 오류 형식의 403을 돌려준다")
    void admin_superApis_forbidden(String path) throws Exception {
        mockMvc.perform(get(path).with(asAdmin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/admins", "/admins/1", "/histories", "/histories/1"})
    @DisplayName("FNC-C-003 ADMIN이 최고 관리자 전용 화면을 열면 403 오류 화면이 나온다")
    void admin_superScreens_forbiddenPage(String path) throws Exception {
        mockMvc.perform(get(path).with(asAdmin()))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("권한이 없습니다.")));
    }

    @Test
    @DisplayName("FNC-C-003 로그인했지만 로그인 아이디·비밀번호 외 경로도 CSRF 토큰 없이 쓰기 요청을 보내면 403이다")
    void admin_postWithoutCsrf_forbidden() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/v1/manage/cameras/1/start").with(asAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FNC-C-003 CSRF 토큰을 같이 보내면 쓰기 요청이 권한 검사를 통과한다")
    void admin_postWithCsrf_allowed() throws Exception {
        assertAllowed(mockMvc.perform(request(HttpMethod.POST, "/api/v1/manage/unknown").with(asAdmin()).with(csrf()))
                .andReturn());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/me/password", "/css/app.css", "/js/app.js", "/images/logo.png", "/favicon.ico"})
    @DisplayName("FNC-C-003 비밀번호 변경이 필요한 계정도 변경 화면과 정적 파일은 쓸 수 있다")
    void mustChangePassword_allowedScreens(String path) throws Exception {
        assertAllowed(mockMvc.perform(get(path).with(asMustChangePassword())).andReturn());
    }

    @Test
    @DisplayName("FNC-C-003 비밀번호 변경이 필요한 계정도 변경 API와 로그아웃은 쓸 수 있다")
    void mustChangePassword_allowedApiAndLogout() throws Exception {
        assertAllowed(mockMvc.perform(request(HttpMethod.PATCH, "/api/v1/me/password")
                        .with(asMustChangePassword())
                        .with(csrf()))
                .andReturn());
        mockMvc.perform(request(HttpMethod.POST, "/logout").with(asMustChangePassword()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_REDIRECT + "?logout"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/stats", "/cameras", "/me", "/admins"})
    @DisplayName("FNC-C-003 비밀번호 변경이 필요한 계정이 다른 화면을 열면 비밀번호 변경 화면으로 보낸다")
    void mustChangePassword_otherScreens_redirectToPasswordChange(String path) throws Exception {
        mockMvc.perform(get(path).with(asMustChangePassword()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", PASSWORD_CHANGE_REDIRECT));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/manage/zones/1", "/api/v1/me", "/api/v1/events"})
    @DisplayName("FNC-C-003 비밀번호 변경이 필요한 계정이 다른 API를 부르면 공통 오류 형식의 403을 돌려준다")
    void mustChangePassword_otherApis_forbidden(String path) throws Exception {
        mockMvc.perform(get(path).with(asMustChangePassword()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
