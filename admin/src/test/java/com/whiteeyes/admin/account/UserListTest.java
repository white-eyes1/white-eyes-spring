package com.whiteeyes.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 사용자 목록 API 검수 기준: 필터 조합·정렬·페이지 이동(FNC-A-002). */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(AdminTestUserConfig.class)
// 지정하지 않으면 JVM 기본 인코딩(Windows에서는 MS949)으로 읽어 한글 이름이 깨진다
@Sql(scripts = "/sql/account/user-list.sql", config = @SqlConfig(encoding = "UTF-8"))
@Sql(
        statements = {"DELETE FROM SPRING_SESSION_USER", "DELETE FROM user_account"},
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class UserListTest {

    private static final String USERS_URL = "/api/v1/users";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 14, 0);

    @TestConfiguration
    static class FixedClockConfig {

        // 시험 데이터의 활동 중 판정이 실행 시각에 따라 바뀌지 않게 현재 시각을 고정한다
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoSpyBean
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @BeforeEach
    void resetSpy() {
        clearInvocations(namedParameterJdbcTemplate);
    }

    @Test
    @DisplayName("FNC-A-002 조건이 없으면 탈퇴 계정까지 전체를 최근 가입 순으로")
    void list_noCondition_allIncludingWithdrawn() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].loginId")
                        .value(contains("pct06", "hong05", "park04", "lee03", "kim02", "hong01")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("FNC-A-002 응답 한 줄은 명세의 칸 이름이고 시각은 +09:00")
    void list_itemShape() throws Exception {
        list("?keyword=lee03")
                .andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(jsonPath("$.items[0].loginId").value("lee03"))
                .andExpect(jsonPath("$.items[0].name").value("이영희"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-12T23:59:59+09:00"))
                .andExpect(jsonPath("$.items[0].lastActiveAt").value("2026-10-21T13:55:00+09:00"))
                .andExpect(jsonPath("$.items[0].active").value(true));
        list("?keyword=hong01")
                .andExpect(jsonPath("$.items[0].email").value("hong@example.com"))
                .andExpect(jsonPath("$.items[0].phone").value("010-1111-1111"));
    }

    @Test
    @DisplayName("FNC-A-002 필터 조합: 상태 / 아이디·이름 검색 / 검색어+상태+가입일")
    void list_filterCombinations() throws Exception {
        list("?status=WITHDRAWN").andExpect(jsonPath("$.items[*].loginId").value(contains("park04")));
        list("?keyword=hong&sort=loginId,asc")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("hong01", "hong05")));
        list("?keyword=홍길&sort=loginId,asc")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("hong01", "hong05")));
        list("?keyword=hong&status=ACTIVE&joinedFrom=2026-10-10")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("hong05")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("FNC-A-002 가입일 끝 날짜는 그날 하루 전체를 포함한다")
    void list_joinedRange_toDayInclusive() throws Exception {
        list("?joinedFrom=2026-10-12&joinedTo=2026-10-12")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("lee03")));
    }

    @Test
    @DisplayName("FNC-A-002 검색어의 %와 _는 글자 그대로 찾는다")
    void list_keywordWildcardsAreLiteral() throws Exception {
        // URL 문자열에 직접 쓰면 %가 한 번 더 인코딩되므로 param으로 넘긴다
        mockMvc.perform(get(USERS_URL).param("keyword", "%"))
                .andExpect(jsonPath("$.items[*].loginId").value(contains("pct06")));
        mockMvc.perform(get(USERS_URL).param("keyword", "_"))
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("FNC-A-002 아이디·가입일 정렬 오름·내림")
    void list_sortByLoginIdAndCreatedAt() throws Exception {
        list("?sort=loginId,asc")
                .andExpect(jsonPath("$.items[*].loginId")
                        .value(contains("hong01", "hong05", "kim02", "lee03", "park04", "pct06")));
        list("?sort=loginId,desc")
                .andExpect(jsonPath("$.items[*].loginId")
                        .value(contains("pct06", "park04", "lee03", "kim02", "hong05", "hong01")));
        list("?sort=createdAt,asc")
                .andExpect(jsonPath("$.items[*].loginId")
                        .value(contains("hong01", "kim02", "lee03", "park04", "hong05", "pct06")));
    }

    @Test
    @DisplayName("FNC-A-002 최근 활동 정렬: 세션이 로그인 시각보다 우선하고, 최근 활동이 없으면 맨 뒤")
    void list_sortByLastActiveAt() throws Exception {
        list("?sort=lastActiveAt,desc")
                .andExpect(jsonPath("$.items[*].loginId")
                        .value(contains("lee03", "hong01", "hong05", "park04", "pct06", "kim02")));
        list("?sort=lastActiveAt,asc")
                .andExpect(jsonPath("$.items[*].loginId")
                        .value(contains("park04", "hong05", "hong01", "lee03", "kim02", "pct06")));
    }

    @Test
    @DisplayName("FNC-A-002 활동 중 필터: 30분 안에 세션 접근이 있을 때만 활동 중(세션 없이 최근 로그인만 있으면 아님, #203)")
    void list_activeFilter() throws Exception {
        list("?active=true")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("lee03")))
                .andExpect(jsonPath("$.totalElements").value(1));
        list("?active=false")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("pct06", "hong05", "park04", "kim02", "hong01")))
                .andExpect(jsonPath("$.totalElements").value(5));
        list("?active=true&status=ACTIVE&keyword=hong").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    @DisplayName("FNC-A-002 페이지 이동: 페이지 크기·전체 페이지 수·범위 밖 페이지")
    void list_paging() throws Exception {
        list("?sort=loginId,asc&size=2&page=1")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("kim02", "lee03")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(3));
        list("?sort=loginId,asc&size=2&page=3")
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(6));
        list("?sort=lastActiveAt,desc&size=4&page=1")
                .andExpect(jsonPath("$.items[*].loginId").value(contains("pct06", "kim02")))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("FNC-A-002 활동 중 필터는 1,000명까지만 다루고 truncated, 일반 목록은 전체를 센다")
    void list_sessionDependentLimit() throws Exception {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_USER");
        jdbcTemplate.update("DELETE FROM user_account");
        List<Object[]> users = IntStream.rangeClosed(1, 1001)
                .mapToObj(i -> new Object[] {"u%04d".formatted(i), "사용자" + i})
                .toList();
        jdbcTemplate.batchUpdate(
                "INSERT INTO user_account (login_id, password, name, status) VALUES (?, 'x', ?, 'ACTIVE')", users);

        list("?active=false")
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.totalElements").value(1000))
                .andExpect(jsonPath("$.totalPages").value(50));
        list("?sort=loginId,asc")
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.totalElements").value(1001));
    }

    @Test
    @DisplayName("FNC-A-002 세션 조회는 요청마다 한 번, 일반 목록의 DB 조회는 목록·개수 두 번")
    void list_queryCounts() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        list("?sort=loginId,asc").andExpect(status().isOk());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        verify(namedParameterJdbcTemplate, times(1)).query(anyString(), anyMap(), any(RowCallbackHandler.class));

        clearInvocations(namedParameterJdbcTemplate);
        list("?active=true").andExpect(status().isOk());
        verify(namedParameterJdbcTemplate, times(1)).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    @Test
    @DisplayName("FNC-A-002 잘못된 조회 조건은 400과 칸별 오류")
    void list_invalidInput() throws Exception {
        expectFieldError("?sort=name,asc", "sort");
        expectFieldError("?status=DELETED", "status");
        expectFieldError("?size=101", "size");
        expectFieldError("?page=-1", "page");
        expectFieldError("?page=abc", "page");
        expectFieldError("?joinedFrom=2026-10-12&joinedTo=2026-10-11", "joinedTo");
        expectFieldError("?joinedFrom=2026/10/12", "joinedFrom");
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get(USERS_URL + query));
    }

    private void expectFieldError(String query, String field) throws Exception {
        list(query)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(contains(field)));
    }
}
