package com.whiteeyes.admin.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.dto.ZonePreviewResponse;
import com.whiteeyes.admin.region.exception.InvalidPolygonException;
import com.whiteeyes.admin.region.exception.OutOfServiceAreaException;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZonePreviewService;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ZonePreviewTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String PREVIEW_URL = "/api/v1/manage/zones/preview";

    // 삼청동(경도 127.00~127.05, 위도 37.60~37.65) 안의 0.002° × 0.002° 사각형.
    // 실제 크기: 위도 방향 약 222.4m, 경도 방향 약 176.2m → 약 39,190㎡
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final double SAMCHEONG_SQUARE_AREA = 39_190.0;

    @Autowired
    private ZonePreviewService zonePreviewService;

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void loadRegions() {
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
    }

    @Test
    @DisplayName("FNC-A-013 알려진 크기의 사각형으로 면적을 확인한다(오차 1% 미만)")
    void preview_knownSquare_areaWithinOnePercent() {
        ZonePreviewResponse response = zonePreviewService.preview(json(SAMCHEONG_SQUARE));

        assertThat(response.areaM2()).isCloseTo(SAMCHEONG_SQUARE_AREA, within(SAMCHEONG_SQUARE_AREA * 0.01));
    }

    @Test
    @DisplayName("FNC-A-013 내부 점이 들어가는 동의 id와 이름을 돌려준다")
    void preview_returnsContainingDong() {
        ZonePreviewResponse response = zonePreviewService.preview(json(SAMCHEONG_SQUARE));

        assertThat(response.dongName()).isEqualTo("삼청동");
        assertThat(response.dongId()).isNotNull();
    }

    @Test
    @DisplayName("FNC-A-013 서비스 지역 밖이면 OUT_OF_SERVICE_AREA")
    void preview_outsideAnyDong_throws() {
        String outside =
                "{\"type\":\"Polygon\",\"coordinates\":[[[128.0,35.0],[128.002,35.0],[128.002,35.002],[128.0,35.002],[128.0,35.0]]]}";

        assertThatThrownBy(() -> zonePreviewService.preview(json(outside)))
                .isInstanceOf(OutOfServiceAreaException.class);
    }

    @Test
    @DisplayName("FNC-A-013 점이 3개 미만이면 INVALID_POLYGON")
    void preview_tooFewPoints_throws() {
        String line = "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.001,37.601]]]}";

        assertThatThrownBy(() -> zonePreviewService.preview(json(line))).isInstanceOf(InvalidPolygonException.class);
    }

    @Test
    @DisplayName("FNC-A-013 자기 교차하는 다각형이면 INVALID_POLYGON")
    void preview_selfIntersecting_throws() {
        String bowTie =
                "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.603],[127.003,37.601],[127.001,37.603],[127.001,37.601]]]}";

        assertThatThrownBy(() -> zonePreviewService.preview(json(bowTie))).isInstanceOf(InvalidPolygonException.class);
    }

    @Test
    @DisplayName("FNC-A-013 Polygon이 아닌 GeoJSON이면 INVALID_POLYGON")
    void preview_notPolygon_throws() {
        String point = "{\"type\":\"Point\",\"coordinates\":[127.001,37.601]}";

        assertThatThrownBy(() -> zonePreviewService.preview(json(point))).isInstanceOf(InvalidPolygonException.class);
    }

    @Test
    @DisplayName("POST /api/v1/manage/zones/preview 정상이면 200과 {areaM2, dongId, dongName}")
    void api_ok() throws Exception {
        mockMvc.perform(post(PREVIEW_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boundary\":" + SAMCHEONG_SQUARE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaM2").isNumber())
                .andExpect(jsonPath("$.dongId").isNumber())
                .andExpect(jsonPath("$.dongName").value("삼청동"));
    }

    @Test
    @DisplayName("POST /api/v1/manage/zones/preview 잘못된 다각형이면 400 INVALID_POLYGON")
    void api_invalidPolygon() throws Exception {
        mockMvc.perform(post(PREVIEW_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boundary\":{\"type\":\"Point\",\"coordinates\":[127.0,37.6]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POLYGON"));
    }

    @Test
    @DisplayName("POST /api/v1/manage/zones/preview 서비스 지역 밖이면 400 OUT_OF_SERVICE_AREA")
    void api_outOfServiceArea() throws Exception {
        String outside =
                "{\"type\":\"Polygon\",\"coordinates\":[[[128.0,35.0],[128.002,35.0],[128.002,35.002],[128.0,35.002],[128.0,35.0]]]}";

        mockMvc.perform(post(PREVIEW_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boundary\":" + outside + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUT_OF_SERVICE_AREA"));
    }

    private JsonNode json(String text) {
        return jsonMapper.readTree(text);
    }
}
