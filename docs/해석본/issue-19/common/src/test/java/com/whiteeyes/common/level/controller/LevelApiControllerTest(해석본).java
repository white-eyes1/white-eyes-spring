/*
 * [해석본] 원본: common/src/test/java/com/whiteeyes/common/level/controller/LevelApiControllerTest.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] GET /api/v1/meta/levels가 api/public.md의 응답 예시와 똑같은 JSON을 주는지 확인한다.
 * [왜 standaloneSetup] 앱 전체(Spring 컨텍스트)를 띄우지 않고 컨트롤러 하나만 붙인 가짜 MVC 환경에서 요청을 보낸다.
 *   common 모듈에는 앱 시작 클래스가 없어서 @WebMvcTest를 쓰기 어렵고, #15·#16의 컨트롤러 테스트도 같은 방식을 썼다.
 *   서비스도 가짜(mock)가 아니라 진짜 LevelService를 넣었다. 계산이 단순해서 진짜를 써도 빠르고, 응답 JSON까지
 *   실제 값으로 끝에서 끝까지 확인할 수 있다.
 */
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

    // [해석] 기대 JSON은 api/public.md의 Response 예시를 그대로 붙여 넣었다. 명세 예시와 응답이 어긋나면 바로 깨진다.
    //        JsonCompareMode.STRICT: 순서와 필드까지 정확히 같아야 통과한다(필드가 하나 더 있어도 실패).
    //        처음에는 json(문자열, true)로 썼는데, 이 방식이 사용 중단(deprecated)되어 빌드 경고가 나서 바꿨다.
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
