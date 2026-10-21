package com.whiteeyes.user;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.repository.RegionRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 지역·구역 목록(FNC-C-008)이 사용자 앱에도 같은 경로로 등록되는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RegionListUserAppTest {

    private static final String BOUNDARY =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.0,37.55],[127.05,37.55],[127.05,37.65],[127.0,37.55]]]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegionRepository regionRepository;

    @AfterEach
    void clear() {
        regionRepository.deleteAll();
    }

    @Test
    @DisplayName("FNC-C-008 사용자 앱에서도 GET /api/v1/regions가 시·도 목록을 준다")
    void regions_registeredInUserApp() throws Exception {
        regionRepository.save(new Region(
                null, RegionLevel.SIDO, "11", "서울특별시", BOUNDARY, new BigDecimal("37.600000"), new BigDecimal("127.020000")));

        mockMvc.perform(get("/api/v1/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("서울특별시")))
                .andExpect(jsonPath("$[0].boundary.type").value("Polygon"));
    }

    @Test
    @DisplayName("FNC-C-008 사용자 앱에서도 GET /api/v1/zones가 등록된다")
    void zones_registeredInUserApp() throws Exception {
        mockMvc.perform(get("/api/v1/zones")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }
}
