package com.whiteeyes.user.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.security.AccountPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 사용자 앱 테스트 DB에는 지역·구역이 없어 전체(ALL) 범위로 받는다(빈 시트). 값은 관리자 앱 StatExportApiTest가 본다
/** 통계 엑셀 다운로드(FNC-C-023)가 사용자 앱에도 등록되고, 작업 기록의 행동한 계정이 사용자(USER)로 남는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class StatExportUserAppTest {

    private static final long USER_ID = 11L;
    private static final String LOGIN_ID = "user01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM work_history");
    }

    @Test
    @WithMockUser(roles = "PENDING")
    @DisplayName("FNC-C-023 승인 대기 계정은 403")
    void pending_forbidden() throws Exception {
        mockMvc.perform(exportRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("FNC-C-023 사용자가 내려받으면 작업 기록의 행동한 계정 종류는 USER")
    void user_recordsUserActor() throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new AccountPrincipal(USER_ID, LOGIN_ID, "encoded", true, false, authorities);

        mockMvc.perform(exportRequest().with(authentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities))))
                .andExpect(status().isOk());

        Map<String, Object> row =
                jdbcTemplate.queryForMap("SELECT actor_type, actor_id, actor_login_id, action FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("USER");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(USER_ID);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(LOGIN_ID);
        assertThat(row.get("ACTION")).isEqualTo("EXCEL_DOWNLOAD");
    }

    private static MockHttpServletRequestBuilder exportRequest() {
        return get("/api/v1/stats/export")
                .param("scope", "ALL")
                .param("interval", "1d")
                .param("from", "2099-01-01")
                .param("to", "2099-01-03")
                .param("items", "COUNT");
    }
}
