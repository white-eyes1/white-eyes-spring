/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/stat/controller/ResultInternalControllerTest.java
 * 관련 이슈: #16(FNC-A-024), API: POST /internal/v1/results
 *
 * [무엇] HTTP 입구의 테스트. JSON이 DTO로 맞게 바뀌는지, 형식이 틀리면 400이 나는지, 성공하면 204인지.
 * [왜 standaloneSetup인가] 보통은 @WebMvcTest로 컨트롤러만 띄운다. 그런데 AdminApplication에 붙은
 *   @EnableJpaRepositories 때문에 @WebMvcTest가 JPA까지 띄우려다 실패한다. standaloneSetup은 컨트롤러 객체 하나만
 *   가짜 서블릿 환경에 올리므로 이 문제가 없고 빠르다. 검증(@Valid, @Size)과 JSON 변환은 그대로 동작한다.
 *   (#15의 CameraInternalControllerTest와 같은 이유·같은 방식)
 */
package com.whiteeyes.admin.stat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest.DirectionCount;
import com.whiteeyes.admin.stat.service.ResultReceiveService;

@ExtendWith(MockitoExtension.class)
class ResultInternalControllerTest {

    private static final String RESULTS_URL = "/internal/v1/results";
    // [해석] api/analyzer.md의 요청 예시를 거의 그대로 옮긴 '올바른 항목 하나'. 여러 테스트가 이것을 조금씩 망가뜨려 쓴다.
    //        """ … """는 Java 텍스트 블록: 여러 줄 문자열을 따옴표 이스케이프 없이 쓸 수 있다.
    private static final String VALID_ITEM = """
            {"cameraId": 7, "at": "2026-10-12T14:03:05+09:00", "personCount": 42,
             "in": {"N": 1, "E": 0, "S": 0, "W": 2}, "out": {"N": 0, "E": 3, "S": 0, "W": 0},
             "dwellSec": [305.2, 41.0]}
            """;

    // [해석] 서비스는 가짜. 이 테스트는 '입구'만 보고, 버리기·누적 같은 로직은 서비스 테스트가 본다.
    @Mock
    private ResultReceiveService resultReceiveService;

    // [해석] Captor: 가짜 서비스가 '어떤 인자로 불렸는지'를 붙잡아 꺼내 보는 도구. JSON → DTO 변환 결과를 확인할 때 쓴다.
    @Captor
    private ArgumentCaptor<List<ResultReceiveRequest>> resultsCaptor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ResultInternalController(resultReceiveService))
                .build();
    }

    // [해석] 정상 경로. 204인지 + JSON의 모든 칸이 DTO로 제대로 옮겨졌는지(특히 "N"→north 같은 키 매핑).
    //        at을 toInstant()로 비교하는 이유: JSON 변환기가 시간대를 UTC로 바꿔 줄 수 있어서(+09:00 → Z),
    //        OffsetDateTime끼리 equals로 비교하면 같은 순간인데도 다르다고 나올 수 있다. '같은 순간'인지만 본다.
    @Test
    @DisplayName("FNC-A-024 결과 묶음을 받으면 204")
    void returnsNoContent() throws Exception {
        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content("[" + VALID_ITEM + "]"))
                .andExpect(status().isNoContent());

        verify(resultReceiveService).receiveResults(resultsCaptor.capture());
        // [해석] get(0): List.getFirst()는 Java 21 기능이라 이 프로젝트(Java 17)에서는 쓸 수 없다.
        ResultReceiveRequest result = resultsCaptor.getValue().get(0);
        assertThat(result.cameraId()).isEqualTo(7L);
        assertThat(result.at().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-12T14:03:05+09:00").toInstant());
        assertThat(result.personCount()).isEqualTo(42);
        assertThat(result.in()).isEqualTo(new DirectionCount(1, 0, 0, 2));
        assertThat(result.out()).isEqualTo(new DirectionCount(0, 3, 0, 0));
        assertThat(result.dwellSec()).containsExactly(305.2, 41.0);
    }

    // [해석] personCount가 빠진 항목 → 400. verifyNoInteractions: 서비스가 아예 불리지 않았는지까지 확인한다.
    //        400만 보고 서비스 호출 여부를 안 보면 '일부는 처리하고 400'인 잘못된 구현도 통과해 버린다.
    @Test
    @DisplayName("FNC-A-024 필수 항목이 없으면 400")
    void returnsBadRequestWhenRequiredFieldMissing() throws Exception {
        String withoutPersonCount = """
                [{"cameraId": 7, "at": "2026-10-12T14:03:05+09:00",
                  "in": {"N": 0, "E": 0, "S": 0, "W": 0}, "out": {"N": 0, "E": 0, "S": 0, "W": 0}, "dwellSec": []}]
                """;

        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content(withoutPersonCount))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(resultReceiveService);
    }

    // [해석] 안쪽 객체(in.N)의 음수 → 400. DTO의 @Valid(안쪽까지 검사)가 실제로 동작하는지 확인하려고 일부러 안쪽 값을 망가뜨렸다.
    @Test
    @DisplayName("FNC-A-024 음수 값이 있으면 400")
    void returnsBadRequestForNegativeCount() throws Exception {
        String negativeIn = VALID_ITEM.replace("\"N\": 1", "\"N\": -1");

        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content("[" + negativeIn + "]"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(resultReceiveService);
    }

    // [해석] 2,001건 → 400. 바로 아래 '2,000건은 받음'과 짝을 이뤄 한도의 경계(2000 OK, 2001 거절)를 확인한다.
    @Test
    @DisplayName("FNC-A-024 한 번에 2,000건을 넘으면 400")
    void returnsBadRequestWhenOverMaxResults() throws Exception {
        String overMax = "[" + String.join(",", Collections.nCopies(2001, VALID_ITEM)) + "]";

        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content(overMax))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(resultReceiveService);
    }

    @Test
    @DisplayName("FNC-A-024 2,000건까지는 받음")
    void acceptsMaxResults() throws Exception {
        String max = "[" + String.join(",", Collections.nCopies(2000, VALID_ITEM)) + "]";

        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content(max))
                .andExpect(status().isNoContent());
    }
}
