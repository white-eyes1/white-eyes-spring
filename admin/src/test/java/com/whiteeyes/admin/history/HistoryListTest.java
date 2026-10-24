package com.whiteeyes.admin.history;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 작업 기록 조회(FNC-A-053) GET /api/v1/histories. 기록 행은 계정·행동·시각이 다양해야 해서 SQL로 직접 넣는다. */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class HistoryListTest {

    private static final String HISTORIES_URL = "/api/v1/histories";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    // 이 API는 최고 관리자만 부른다. 기본 사용자(ADMIN)는 403 시험에 그대로 쓴다
    private static MockHttpServletRequestBuilder asSuperAdmin(MockHttpServletRequestBuilder request) {
        return request.with(user("super01").roles("SUPER_ADMIN"));
    }

    @BeforeEach
    @AfterEach
    void clearHistories() {
        // 다른 테스트가 센 작업 기록 건수에 영향을 주지 않게 시작과 끝에 비운다
        jdbcTemplate.update("DELETE FROM work_history");
    }

    @Test
    @DisplayName("FNC-A-053 기록이 없으면 빈 페이지")
    void list_noHistories_emptyPage() throws Exception {
        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("FNC-A-053 행에 시각(+09:00)·계정·행동·대상·상세가 담긴다")
    void list_returnsRowFields() throws Exception {
        long id = insert("ADMIN", "admin02", "EVENT_STATUS", "EVENT", 101L, "궁동 1구역 혼잡",
                "{\"from\":\"UNCHECKED\",\"to\":\"CHECKED\"}", T0);

        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].at").value(endsWith("2026-10-12T09:00:00+09:00")))
                .andExpect(jsonPath("$.items[0].actorType").value("ADMIN"))
                .andExpect(jsonPath("$.items[0].loginId").value("admin02"))
                .andExpect(jsonPath("$.items[0].action").value("EVENT_STATUS"))
                .andExpect(jsonPath("$.items[0].targetType").value("EVENT"))
                .andExpect(jsonPath("$.items[0].targetId").value(101))
                .andExpect(jsonPath("$.items[0].targetName").value("궁동 1구역 혼잡"))
                .andExpect(jsonPath("$.items[0].detail.from").value("UNCHECKED"))
                .andExpect(jsonPath("$.items[0].detail.to").value("CHECKED"));
    }

    @Test
    @DisplayName("FNC-A-053 대상·상세가 없는 기록(전체 엑셀 다운로드)은 해당 값이 null")
    void list_rowWithoutTargetAndDetail_nullFields() throws Exception {
        insert("USER", "parkjw", "EXCEL_DOWNLOAD", "ALL", null, null, null, T0);

        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetType").value("ALL"))
                .andExpect(jsonPath("$.items[0].targetId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].targetName").value(nullValue()))
                .andExpect(jsonPath("$.items[0].detail").value(nullValue()));
    }

    @Test
    @DisplayName("FNC-A-053 최신순, 같은 시각이면 번호가 큰 것이 먼저")
    void list_latestFirst_tieBrokenByIdDescending() throws Exception {
        long oldest = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0);
        long sameTimeFirst = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 2L, "z", null, T0.plusHours(1));
        long sameTimeSecond = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 3L, "z", null, T0.plusHours(1));
        long newest = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 4L, "z", null, T0.plusHours(2));

        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id")
                        .value(contains((int) newest, (int) sameTimeSecond, (int) sameTimeFirst, (int) oldest)));
    }

    @Test
    @DisplayName("FNC-A-053 기간은 from 이상 to 미만")
    void list_period_fromInclusiveToExclusive() throws Exception {
        insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0.minusSeconds(1));
        long atFrom = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 2L, "z", null, T0);
        long inside = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 3L, "z", null, T0.plusHours(5));
        insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 4L, "z", null, T0.plusDays(1));

        list("from", "2026-10-12T09:00:00+09:00", "to", "2026-10-13T09:00:00+09:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) inside, (int) atFrom)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("FNC-A-053 from만 주거나 to만 줘도 그쪽으로만 거른다")
    void list_period_oneSided() throws Exception {
        long early = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0);
        long late = insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 2L, "z", null, T0.plusDays(2));

        list("from", "2026-10-13T00:00:00+09:00")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) late)));
        list("to", "2026-10-13T00:00:00+09:00")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) early)));
    }

    @Test
    @DisplayName("FNC-A-053 같은 아이디가 사용자·관리자 양쪽에 있으면 계정 종류와 함께 거른다")
    void list_actorTypeAndLoginId_separatesSameLoginId() throws Exception {
        long admin = insert("ADMIN", "kim01", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0);
        long user = insert("USER", "kim01", "EXCEL_DOWNLOAD", "ALL", null, null, null, T0.plusMinutes(1));
        insert("ADMIN", "lee01", "ZONE_CHANGE", "ZONE", 2L, "z", null, T0.plusMinutes(2));

        list("actorType", "ADMIN", "loginId", "kim01")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) admin)));
        list("actorType", "USER", "loginId", "kim01")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) user)));
    }

    @Test
    @DisplayName("FNC-A-053 계정 종류를 비우고 아이디만 주면 두 종류에서 그 아이디를 찾는다")
    void list_loginIdOnly_matchesBothActorTypes() throws Exception {
        long admin = insert("ADMIN", "kim01", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0);
        long user = insert("USER", "kim01", "EXCEL_DOWNLOAD", "ALL", null, null, null, T0.plusMinutes(1));
        insert("ADMIN", "kim012", "ZONE_CHANGE", "ZONE", 2L, "z", null, T0.plusMinutes(2));

        list("loginId", "kim01")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) user, (int) admin)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("FNC-A-053 계정 종류만 주면 그 종류의 기록만")
    void list_actorTypeOnly() throws Exception {
        insert("ADMIN", "kim01", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0);
        long user = insert("USER", "park01", "EXCEL_DOWNLOAD", "ALL", null, null, null, T0);

        list("actorType", "USER").andExpect(jsonPath("$.items[*].id").value(contains((int) user)));
    }

    @Test
    @DisplayName("FNC-A-053 행동 종류 여섯 가지를 각각 거른다")
    void list_action_eachOfSixValues() throws Exception {
        String[] actions = {
            "EXCEL_DOWNLOAD", "EVENT_STATUS", "ACCOUNT_CHANGE", "CAMERA_RUN", "ZONE_CHANGE", "CAMERA_CHANGE"
        };
        for (int index = 0; index < actions.length; index++) {
            insert("ADMIN", "a1", actions[index], "ALL", null, null, null, T0.plusMinutes(index));
        }

        for (String action : actions) {
            list("action", action)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].action").value(action));
        }
    }

    @Test
    @DisplayName("FNC-A-053 기간·계정·행동 조건은 함께 줄 수 있다")
    void list_combinedFilters() throws Exception {
        insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", 1L, "z", null, T0);
        long hit = insert("ADMIN", "a1", "CAMERA_RUN", "CAMERA", 2L, "c", null, T0.plusHours(1));
        insert("ADMIN", "a2", "CAMERA_RUN", "CAMERA", 3L, "c", null, T0.plusHours(1));
        insert("ADMIN", "a1", "CAMERA_RUN", "CAMERA", 4L, "c", null, T0.plusDays(3));

        list("from", "2026-10-12T00:00:00+09:00", "to", "2026-10-13T00:00:00+09:00",
                        "actorType", "ADMIN", "loginId", "a1", "action", "CAMERA_RUN")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) hit)));
    }

    @Test
    @DisplayName("FNC-A-053 페이지: 쪽 번호·크기에 맞게 나누고 전체 개수·쪽 수를 준다")
    void list_paging() throws Exception {
        for (int index = 0; index < 5; index++) {
            insert("ADMIN", "a1", "ZONE_CHANGE", "ZONE", (long) index, "z", null, T0.plusMinutes(index));
        }

        list("page", "0", "size", "2")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].targetId").value(4));
        list("page", "2", "size", "2")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].targetId").value(0));
        list("page", "3", "size", "2").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    @DisplayName("FNC-A-053 시작이 끝보다 늦거나 같으면 400")
    void list_fromNotBeforeTo_badRequest() throws Exception {
        list("from", "2026-10-13T00:00:00+09:00", "to", "2026-10-12T00:00:00+09:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        list("from", "2026-10-12T00:00:00+09:00", "to", "2026-10-12T00:00:00+09:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-A-053 쪽·크기 범위를 벗어나거나 코드 값이 아니거나 아이디가 20자를 넘으면 400과 fieldErrors")
    void list_invalidParameters_badRequestWithFieldErrors() throws Exception {
        list("size", "0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        list("size", "101").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        list("page", "-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        list("actorType", "ROBOT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("actorType"));
        list("action", "LOGIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("action"));
        list("loginId", "a".repeat(21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("loginId"));
    }

    @Test
    @DisplayName("FNC-A-053 관리자(ADMIN)는 403, 로그인하지 않으면 401")
    void list_notSuperAdmin_rejected() throws Exception {
        mockMvc.perform(get(HISTORIES_URL)).andExpect(status().isForbidden());
        mockMvc.perform(get(HISTORIES_URL).with(anonymous())).andExpect(status().isUnauthorized());
    }

    private ResultActions list(String... paramsNameValue) throws Exception {
        MockHttpServletRequestBuilder request = asSuperAdmin(get(HISTORIES_URL));
        for (int index = 0; index < paramsNameValue.length; index += 2) {
            request.param(paramsNameValue[index], paramsNameValue[index + 1]);
        }
        return mockMvc.perform(request);
    }

    // detail은 JSON 칼럼이라 H2에서는 FORMAT JSON을 붙여야 객체로 저장된다
    private long insert(
            String actorType,
            String loginId,
            String action,
            String targetType,
            Long targetId,
            String targetName,
            String detail,
            LocalDateTime createdAt) {
        String detailSql = detail == null ? "NULL" : "? FORMAT JSON";
        String sql = "INSERT INTO work_history (actor_type, actor_id, actor_login_id, action, target_type, target_id,"
                + " target_name, detail, created_at) VALUES (?, 1, ?, ?, ?, ?, ?, " + detailSql + ", ?)";
        if (detail == null) {
            jdbcTemplate.update(sql, actorType, loginId, action, targetType, targetId, targetName, createdAt);
        } else {
            jdbcTemplate.update(sql, actorType, loginId, action, targetType, targetId, targetName, detail, createdAt);
        }
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM work_history", Long.class);
    }
}
