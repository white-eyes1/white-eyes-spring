package com.whiteeyes.admin.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class ZoneRegisterTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String ZONES_URL = "/api/v1/manage/zones";

    // 삼청동(경도 127.00~127.05, 위도 37.55~37.65) 안의 0.002° × 0.002° 사각형, 약 39,190㎡
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final double SAMCHEONG_SQUARE_AREA = 39_190.0;
    // 소공동(경도 127.05~127.10, 위도 37.55~37.60) 안의 사각형
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final String OUTSIDE_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[128.0,35.0],[128.002,35.0],[128.002,35.002],[128.0,35.002],[128.0,35.0]]]}";
    private static final String BOW_TIE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.603],[127.003,37.601],[127.001,37.603],[127.001,37.601]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void loadRegions() {
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
    }

    // zone이 region을 가리키는 외래키라, 남겨 두면 뒤에 도는 지역 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearZones() {
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-014 구역을 만들면 201과 {id, dongId, name, areaM2}를 돌려주고 zone에 저장한다")
    void register_createsZone() throws Exception {
        register("삼청동 1구역", SAMCHEONG_SQUARE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.dongId").isNumber())
                .andExpect(jsonPath("$.name").value("삼청동 1구역"))
                .andExpect(jsonPath("$.areaM2").isNumber());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT z.dong_id, z.name, z.area_m2, z.center_lat, z.center_lng, z.deleted_at, z.created_at,"
                        + " z.updated_at, r.name AS dong_name FROM zone z JOIN region r ON r.id = z.dong_id");
        assertThat(row.get("NAME")).isEqualTo("삼청동 1구역");
        assertThat(row.get("DONG_NAME")).isEqualTo("삼청동");
        assertThat(((Number) row.get("AREA_M2")).doubleValue())
                .isCloseTo(SAMCHEONG_SQUARE_AREA, within(SAMCHEONG_SQUARE_AREA * 0.01));
        assertThat(row.get("DELETED_AT")).isNull();
        assertThat(row.get("CREATED_AT")).isNotNull();
        assertThat(row.get("UPDATED_AT")).isNotNull();
    }

    @Test
    @DisplayName("FNC-A-014 중심점은 다각형 안의 점으로 계산해 저장한다")
    void register_centerIsInsidePolygon() throws Exception {
        register("삼청동 1구역", SAMCHEONG_SQUARE).andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT center_lat, center_lng FROM zone");
        assertThat(((Number) row.get("CENTER_LNG")).doubleValue()).isBetween(127.001, 127.003);
        assertThat(((Number) row.get("CENTER_LAT")).doubleValue()).isBetween(37.601, 37.603);
    }

    @Test
    @DisplayName("FNC-A-014 면적·소속 동은 요청 값이 아니라 서버가 계산한 값을 쓴다")
    void register_ignoresClientCalculatedValues() throws Exception {
        String body = "{\"name\":\"삼청동 1구역\",\"boundary\":" + SAMCHEONG_SQUARE + ",\"areaM2\":1,\"dongId\":999999}";

        mockMvc.perform(post(ZONES_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dongId").value(Long.valueOf(samcheongDongId()).intValue()));

        Number area = jdbcTemplate.queryForObject("SELECT area_m2 FROM zone", Number.class);
        assertThat(area.doubleValue()).isGreaterThan(30_000);
    }

    @Test
    @DisplayName("FNC-A-014 이름 앞뒤 공백은 지우고 저장한다")
    void register_trimsName() throws Exception {
        register("  삼청동 1구역  ", SAMCHEONG_SQUARE).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM zone", String.class)).isEqualTo("삼청동 1구역");
    }

    @Test
    @DisplayName("FNC-A-014 같은 동의 삭제되지 않은 구역과 이름이 같으면 409 DUPLICATE_ZONE_NAME")
    void register_duplicateNameInSameDong_conflict() throws Exception {
        register("삼청동 1구역", SAMCHEONG_SQUARE).andExpect(status().isCreated());

        register("삼청동 1구역", SAMCHEONG_SQUARE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ZONE_NAME"));

        assertThat(zoneCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-014 다른 동이면 같은 이름을 쓸 수 있다")
    void register_sameNameInOtherDong_allowed() throws Exception {
        register("1구역", SAMCHEONG_SQUARE).andExpect(status().isCreated());

        register("1구역", SOGONG_SQUARE).andExpect(status().isCreated());

        assertThat(zoneCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-014 삭제된 구역의 이름은 다시 쓸 수 있다")
    void register_nameOfDeletedZone_allowed() throws Exception {
        register("삼청동 1구역", SAMCHEONG_SQUARE).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP");

        register("삼청동 1구역", SAMCHEONG_SQUARE).andExpect(status().isCreated());

        assertThat(zoneCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-014 잘못된 다각형이면 400 INVALID_POLYGON이고 저장하지 않는다")
    void register_invalidPolygon_badRequest() throws Exception {
        register("꼬인 구역", BOW_TIE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POLYGON"));

        assertThat(zoneCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-014 서비스 지역 밖이면 400 OUT_OF_SERVICE_AREA이고 저장하지 않는다")
    void register_outsideServiceArea_badRequest() throws Exception {
        register("지역 밖 구역", OUTSIDE_SQUARE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUT_OF_SERVICE_AREA"));

        assertThat(zoneCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-014 이름이 비어 있으면 400 INVALID_INPUT과 fieldErrors")
    void register_blankName_badRequest() throws Exception {
        register("   ", SAMCHEONG_SQUARE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        assertThat(zoneCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-014 이름이 50자를 넘으면 400 INVALID_INPUT")
    void register_nameTooLong_badRequest() throws Exception {
        register("가".repeat(51), SAMCHEONG_SQUARE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(zoneCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-014 boundary가 없으면 400 INVALID_INPUT")
    void register_missingBoundary_badRequest() throws Exception {
        mockMvc.perform(post(ZONES_URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"삼청동 1구역\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("boundary"));

        assertThat(zoneCount()).isZero();
    }

    private ResultActions register(String name, String boundary) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"boundary\":" + boundary + "}";
        return mockMvc.perform(post(ZONES_URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long samcheongDongId() {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = '삼청동'", Long.class);
    }

    private long zoneCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM zone", Long.class);
    }
}
