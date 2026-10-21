package com.whiteeyes.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내부 API 토큰 확인(FNC-C-003). 토큰 값은 테스트용 가짜다.
 */
class InternalTokenFilterTest {

    private static final String TOKEN = "fake-internal-token-for-test-0123456789";
    private static final String HEADER = "X-Internal-Token";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private MockHttpServletResponse run(String configuredToken, String sentToken, FilterChain chain) throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(configuredToken, jsonMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/results");
        if (sentToken != null) {
            request.addHeader(HEADER, sentToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("FNC-C-003 토큰이 같으면 다음 단계로 넘긴다")
    void sameToken_passesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(TOKEN, TOKEN, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("FNC-C-003 토큰이 다르거나 없으면 401 공통 오류 응답이고 다음 단계로 넘기지 않는다")
    void differentOrMissingToken_unauthorized() throws Exception {
        for (String sent : new String[] {TOKEN + "x", TOKEN.substring(1), "", null}) {
            MockFilterChain chain = new MockFilterChain();

            MockHttpServletResponse response = run(TOKEN, sent, chain);

            assertThat(response.getStatus()).as("보낸 값: %s", sent).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"").doesNotContain(TOKEN);
        }
    }

    @Test
    @DisplayName("FNC-C-003 서버에 설정된 토큰이 비어 있으면 빈 헤더를 포함해 모든 요청을 거부한다")
    void blankConfiguredToken_rejectsEverything() throws Exception {
        for (String configured : new String[] {"", "   ", null}) {
            for (String sent : new String[] {"", "   ", null, TOKEN}) {
                MockFilterChain chain = new MockFilterChain();

                MockHttpServletResponse response = run(configured, sent, chain);

                assertThat(response.getStatus()).isEqualTo(401);
                assertThat(chain.getRequest()).isNull();
            }
        }
    }
}
