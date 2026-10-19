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
    private static final String VALID_ITEM = """
            {"cameraId": 7, "at": "2026-10-12T14:03:05+09:00", "personCount": 42,
             "in": {"N": 1, "E": 0, "S": 0, "W": 2}, "out": {"N": 0, "E": 3, "S": 0, "W": 0},
             "dwellSec": [305.2, 41.0]}
            """;

    @Mock
    private ResultReceiveService resultReceiveService;

    @Captor
    private ArgumentCaptor<List<ResultReceiveRequest>> resultsCaptor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ResultInternalController(resultReceiveService))
                .build();
    }

    @Test
    @DisplayName("FNC-A-024 결과 묶음을 받으면 204")
    void returnsNoContent() throws Exception {
        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content("[" + VALID_ITEM + "]"))
                .andExpect(status().isNoContent());

        verify(resultReceiveService).receiveResults(resultsCaptor.capture());
        ResultReceiveRequest result = resultsCaptor.getValue().get(0);
        assertThat(result.cameraId()).isEqualTo(7L);
        assertThat(result.at().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-12T14:03:05+09:00").toInstant());
        assertThat(result.personCount()).isEqualTo(42);
        assertThat(result.in()).isEqualTo(new DirectionCount(1, 0, 0, 2));
        assertThat(result.out()).isEqualTo(new DirectionCount(0, 3, 0, 0));
        assertThat(result.dwellSec()).containsExactly(305.2, 41.0);
    }

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

    @Test
    @DisplayName("FNC-A-024 음수 값이 있으면 400")
    void returnsBadRequestForNegativeCount() throws Exception {
        String negativeIn = VALID_ITEM.replace("\"N\": 1", "\"N\": -1");

        mockMvc.perform(post(RESULTS_URL).contentType(MediaType.APPLICATION_JSON).content("[" + negativeIn + "]"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(resultReceiveService);
    }

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
