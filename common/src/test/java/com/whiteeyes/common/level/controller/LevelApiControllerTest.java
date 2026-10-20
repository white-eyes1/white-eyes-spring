package com.whiteeyes.common.level.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.whiteeyes.common.level.service.LevelService;

class LevelApiControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new LevelApiController(new LevelService(new BigDecimal("0.7"), new BigDecimal("1.5"))))
            .build();

    @Test
    @DisplayName("FNC-C-025 GET /api/v1/meta/levels는 api/public.md 예시와 같은 단계 기준을 준다")
    void getLevels_defaultThresholds_matchesApiSpec() throws Exception {
        mockMvc.perform(get("/api/v1/meta/levels"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"level": 1, "name": "정상", "color": "#3BC99B", "minDensity": 0},
                         {"level": 2, "name": "주의", "color": "#F59E0B", "minDensity": 0.7},
                         {"level": 3, "name": "위험", "color": "#EF4D55", "minDensity": 1.5}]
                        """, JsonCompareMode.STRICT));
    }
}
