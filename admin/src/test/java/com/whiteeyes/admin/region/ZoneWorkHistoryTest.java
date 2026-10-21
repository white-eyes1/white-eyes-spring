package com.whiteeyes.admin.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.support.AdminTestLogin;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 구역 등록의 작업 기록 연결(FNC-A-014 처리 순서 5, FNC-C-026). */
@SpringBootTest(properties = "spring.session.jdbc.table-name=SPRING_SESSION_ADMIN")
@AutoConfigureMockMvc
class ZoneWorkHistoryTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String ZONES_URL = "/api/v1/manage/zones";
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String PASSWORD = "test-password-1!";
    private static final String LOGIN_ID = "history-admin";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void prepare() {
        clearAll();
        regionLoadService.load(REGION_FILE);
    }

    // zone·work_history·세션을 남기면 뒤에 도는 다른 테스트가 region을 지우다 실패하거나 기록 수가 어긋난다
    @AfterEach
    void cleanUp() {
        clearAll();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ADMIN");
        jdbcTemplate.update("DELETE FROM admin_account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    @DisplayName("FNC-A-014 구역을 만들면 work_history에 ZONE_CHANGE 1행이 생기고 행동한 계정·대상·detail이 들어간다")
    void register_recordsZoneChange() throws Exception {
        register(AdminTestLogin.admin()).andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        Map<String, Object> zone = jdbcTemplate.queryForMap("SELECT id, dong_id, name, area_m2 FROM zone");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        assertThat(row.get("ACTION")).isEqualTo("ZONE_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ZONE");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(((Number) zone.get("ID")).longValue());
        assertThat(row.get("TARGET_NAME")).isEqualTo("삼청동 1구역");

        // H2는 JSON 칼럼을 바이트로 돌려준다
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.isObject()).as("문자열로 한 번 더 감싸지 않은 JSON 객체").isTrue();
        assertThat(detail.get("op").asString()).isEqualTo("CREATE");
        assertThat(detail.get("name").asString()).isEqualTo("삼청동 1구역");
        assertThat(detail.get("dongId").asLong()).isEqualTo(((Number) zone.get("DONG_ID")).longValue());
        assertThat(detail.get("areaM2").asDouble()).isEqualTo(((Number) zone.get("AREA_M2")).doubleValue());
    }

    @Test
    @DisplayName("FNC-C-026 로그인하지 않으면 401 UNAUTHORIZED이고 구역도 작업 기록도 생기지 않는다")
    void register_notLoggedIn_unauthorized() throws Exception {
        register(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(count("zone")).isZero();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 구역 등록도 취소된다(같은 트랜잭션)")
    void register_historyFails_zoneRolledBack() throws Exception {
        // actor_login_id는 VARCHAR(20)이라 21자는 저장 단계에서 실패한다
        register(AdminTestLogin.admin("a".repeat(21))).andExpect(status().isInternalServerError());

        assertThat(count("zone")).isZero();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 실제 관리자 로그인 세션으로 요청하면 그 계정 번호·아이디가 기록된다")
    void register_withRealLoginSession_recordsThatAccount() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO admin_account (login_id, password, name, role, status, must_change_password)"
                        + " VALUES (?, ?, '기록 테스트', 'ADMIN', 'ACTIVE', 0)",
                LOGIN_ID,
                passwordEncoder.encode(PASSWORD));
        long accountId =
                jdbcTemplate.queryForObject("SELECT id FROM admin_account WHERE login_id = ?", Long.class, LOGIN_ID);

        Cookie session = mockMvc.perform(post("/admin/login")
                        .contextPath("/admin")
                        .param("username", LOGIN_ID)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getCookie("SESSION");
        assertThat(session).as("로그인 뒤 세션 쿠키").isNotNull();

        mockMvc.perform(post("/admin" + ZONES_URL)
                        .contextPath("/admin")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .with(csrf()))
                .andExpect(status().isCreated());

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT actor_type, actor_id, actor_login_id FROM work_history");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) rows.get(0).get("ACTOR_ID")).longValue()).isEqualTo(accountId);
        assertThat(rows.get(0).get("ACTOR_LOGIN_ID")).isEqualTo(LOGIN_ID);
    }

    private ResultActions register(RequestPostProcessor login) throws Exception {
        MockHttpServletRequestBuilder request =
                post(ZONES_URL).contentType(MediaType.APPLICATION_JSON).content(body());
        MockHttpServletRequestBuilder withCsrf = request.with(csrf());
        return mockMvc.perform(login == null ? withCsrf : withCsrf.with(login));
    }

    private String body() {
        return "{\"name\":\"삼청동 1구역\",\"boundary\":" + SAMCHEONG_SQUARE + "}";
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
