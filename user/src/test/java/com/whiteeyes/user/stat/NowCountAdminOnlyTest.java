package com.whiteeyes.user.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.repository.RegionRepository;
import com.whiteeyes.common.stat.service.NowCountSource;
import com.whiteeyes.common.stat.service.StatStreamBroadcaster;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 5초 현재 인원(FNC-A-054, 결정 0018)은 관리자 앱만 낸다. 사용자 앱에는 현재 인원을 내는 빈이 없어
 * currentCount가 null이고 event: now 도 보내지 않으며, 화면은 지금처럼 1분 평균(countAvg)을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
class NowCountAdminOnlyTest {

    private static final String BOUNDARY =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.0,37.55],[127.05,37.55],[127.05,37.65],[127.0,37.55]]]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private StatStreamBroadcaster broadcaster;

    @Autowired
    private RegionRepository regionRepository;

    @AfterEach
    void clear() {
        broadcaster.stop();
        regionRepository.deleteAll();
    }

    @Test
    @DisplayName("FNC-A-054 사용자 앱에는 현재 인원을 내는 빈이 없다")
    void noNowCountSourceBean() {
        assertThat(context.getBeanProvider(NowCountSource.class).getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("FNC-C-016 사용자 앱의 요약은 currentCount가 null이다(countAvg 기준 유지)")
    void summary_currentCountIsNull() throws Exception {
        Region sido = regionRepository.save(new Region(
                null, RegionLevel.SIDO, "11", "서울특별시", BOUNDARY, new BigDecimal("37.600000"), new BigDecimal("127.020000")));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .param("targetType", "REGION")
                        .param("targetId", String.valueOf(sido.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCount").isEmpty());
    }

    @Test
    @DisplayName("FNC-C-022 사용자 앱은 구독자가 있어도 event: now 를 보내지 않는다")
    void stream_noNowEvent() {
        broadcaster.subscribe(null);

        assertThat(broadcaster.pollNowOnce(LocalDateTime.of(2099, 1, 1, 12, 0, 0))).isZero();
    }
}
