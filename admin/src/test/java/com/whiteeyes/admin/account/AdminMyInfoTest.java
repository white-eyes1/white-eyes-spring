package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.history.dto.WorkHistoryCommand;
import com.whiteeyes.common.history.service.WorkHistoryService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 앱 내 정보 조회·수정(FNC-C-006). 실제 로그인으로 만든 세션 쿠키로 요청한다(AdminPasswordChangeTest와 같은 방식).
 */
// 테스트용 application.yml이 main의 것을 가리므로 세션 테이블 이름은 main과 같은 값을 여기서 준다
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class AdminMyInfoTest {

    private static final String CONTEXT_PATH = "/admin";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String LOGIN_ID = "admin01";
    private static final String PASSWORD = "test-password-1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private WorkHistoryService workHistoryService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void insertAdmin() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account");
        insertAdmin(LOGIN_ID, "관리자 본인", "ADMIN", false);
        insertAdmin("admin02", "다른 관리자", "ADMIN", false);
    }

    private void insertAdmin(String loginId, String name, String role, boolean mustChangePassword) {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, email, phone, role, status, must_change_password)"
                        + " VALUES (?, ?, ?, 'admin@example.com', '010-1111-2222', ?, 'ACTIVE', ?)",
                loginId,
                passwordEncoder.encode(PASSWORD),
                name,
                role,
                mustChangePassword);
    }

    private Cookie login() throws Exception {
        Cookie cookie = mockMvc.perform(post("/admin/login")
                        .contextPath(CONTEXT_PATH)
                        .param("username", LOGIN_ID)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);
        assertThat(cookie).as("로그인 세션 쿠키").isNotNull();
        return cookie;
    }

    private ResultActions getMe(Cookie session) throws Exception {
        return mockMvc.perform(get("/admin/api/v1/me").contextPath(CONTEXT_PATH).cookie(session));
    }

    private ResultActions patchMe(Cookie session, String body) throws Exception {
        return mockMvc.perform(patch("/admin/api/v1/me")
                .contextPath(CONTEXT_PATH)
                .cookie(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String column(String loginId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM admin_account WHERE login_id = ?", String.class, loginId);
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 내 정보를 조회하면 role이 함께 나온다")
    void get_includesRole() throws Exception {
        getMe(login())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.name").value("관리자 본인"))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.phone").value("010-1111-2222"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 수정하면 다시 조회한 값에 반영되고 다른 관리자는 그대로다")
    void patch_updatesOnlyOwnRow() throws Exception {
        Cookie session = login();

        patchMe(session, """
                {"name":"새 이름","email":"new@example.com","phone":"010-3333-4444"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        getMe(session).andExpect(jsonPath("$.name").value("새 이름")).andExpect(jsonPath("$.email").value("new@example.com"));
        assertThat(column("admin02", "name")).isEqualTo("다른 관리자");
        assertThat(column("admin02", "email")).isEqualTo("admin@example.com");
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 보낸 필드만 고치고 빈 문자열은 비운다")
    void patch_partialAndClear() throws Exception {
        patchMe(login(), """
                {"name":"이름만","phone":""}""")
                .andExpect(status().isOk());

        assertThat(column(LOGIN_ID, "name")).isEqualTo("이름만");
        assertThat(column(LOGIN_ID, "email")).isEqualTo("admin@example.com");
        assertThat(column(LOGIN_ID, "phone")).isNull();
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 검증 실패는 400 fieldErrors이고 아이디·역할·상태는 바뀌지 않는다")
    void patch_invalidAndImmutableFields() throws Exception {
        Cookie session = login();

        patchMe(session, """
                {"name":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        patchMe(session, """
                {"name":"정상","loginId":"hacker","role":"SUPER_ADMIN","status":"DISABLED"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(column(LOGIN_ID, "role")).isEqualTo("ADMIN");
    }

    private int historyCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_history", Integer.class);
    }

    @Test
    @DisplayName("FNC-C-006·026 관리자 앱: 수정하면 ACCOUNT_CHANGE 1행(행동·대상 모두 본인 관리자, 이름은 아이디, 바뀐 필드 이전·이후)이 남는다")
    void patch_recordsAccountChange() throws Exception {
        patchMe(login(), """
                {"name":"새 이름","email":"admin@example.com","phone":"010-3333-4444"}""")
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM admin_account WHERE login_id = ?", Long.class, LOGIN_ID);
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(LOGIN_ID);
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ADMIN_ACCOUNT");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(adminId);
        assertThat(row.get("TARGET_NAME")).isEqualTo(LOGIN_ID);
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("before").get("name").asString()).isEqualTo("관리자 본인");
        assertThat(detail.get("after").get("name").asString()).isEqualTo("새 이름");
        assertThat(detail.get("before").get("phone").asString()).isEqualTo("010-1111-2222");
        assertThat(detail.get("after").get("phone").asString()).isEqualTo("010-3333-4444");
        assertThat(detail.get("before").has("email")).as("바뀌지 않은 메일은 기록하지 않는다").isFalse();
    }

    @Test
    @DisplayName("FNC-C-006·026 관리자 앱: 바뀐 값이 없으면 기록하지 않는다")
    void patch_unchanged_doesNotRecord() throws Exception {
        patchMe(login(), """
                {"name":"관리자 본인","email":"admin@example.com","phone":"010-1111-2222"}""")
                .andExpect(status().isOk());

        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 관리자 앱: 기록 저장이 실패하면 수정도 취소된다(같은 트랜잭션)")
    void patch_recordFails_rollsBackUpdate() throws Exception {
        Cookie session = login();
        // 서비스는 @Transactional 프록시라 스텁 호출이 트랜잭션 검사에 걸린다. 프록시 속 spy에 직접 건다
        doThrow(new IllegalStateException("record failed"))
                .when((WorkHistoryService) AopTestUtils.getUltimateTargetObject(workHistoryService))
                .record(any(WorkHistoryCommand.class));

        patchMe(session, """
                {"name":"새 이름"}""")
                .andExpect(status().isInternalServerError());

        assertThat(column(LOGIN_ID, "name")).isEqualTo("관리자 본인");
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("FNC-C-006 관리자 앱: 로그인하지 않으면 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/api/v1/me").contextPath(CONTEXT_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SCR-C-002 관리자 앱: /admin/me 화면에 내 정보 입력 칸과 비밀번호 영역이 있다")
    void mePage_rendersProfileAndPasswordArea() throws Exception {
        mockMvc.perform(get("/admin/me").contextPath(CONTEXT_PATH).cookie(login()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"profile-form\"")))
                .andExpect(content().string(containsString("id=\"password-form\"")));
    }
}
