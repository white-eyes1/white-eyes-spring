package com.whiteeyes.admin.region;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** 지역·구역 목록(FNC-C-008). common 컨트롤러를 관리자 앱 컨텍스트에서 DB(H2+Flyway)와 함께 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RegionListTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String REGIONS_URL = "/api/v1/regions";
    private static final String ZONES_URL = "/api/v1/zones";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";

    // 삼청동(종로구) 안의 사각형 둘, 소공동(중구) 안의 사각형 하나
    private static final String SAMCHEONG_SQUARE_A =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SAMCHEONG_SQUARE_B =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.004,37.601],[127.006,37.601],[127.006,37.603],[127.004,37.603],[127.004,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private CameraRegisterService cameraRegisterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long samcheongZoneA;
    private long samcheongZoneB;
    private long sogongZone;

    @BeforeEach
    void prepare() {
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZoneA = createZone("삼청동 1구역", SAMCHEONG_SQUARE_A);
        samcheongZoneB = createZone("삼청동 2구역", SAMCHEONG_SQUARE_B);
        sogongZone = createZone("소공동 1구역", SOGONG_SQUARE);
    }

    // camera → zone → region 순으로 외래키가 걸려 있어 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-C-008 parentId가 없으면 시·도 목록만 준다")
    void regions_withoutParent_returnsSidoOnly() throws Exception {
        regions(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(regionId("11")))
                .andExpect(jsonPath("$[0].level").value("SIDO"))
                .andExpect(jsonPath("$[0].code").value("11"))
                .andExpect(jsonPath("$[0].name").value("서울특별시"));
    }

    @Test
    @DisplayName("FNC-C-008 시·도 → 시·군·구 → 읍·면·동 → 구역 순으로 내려가며 목록이 맞다")
    void descend_sidoToZone_listsMatchHierarchy() throws Exception {
        regions(regionId("11"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].level", contains("SIGUNGU", "SIGUNGU")))
                .andExpect(jsonPath("$[*].code", contains("11110", "11140")))
                .andExpect(jsonPath("$[*].name", contains("종로구", "중구")));

        regions(regionId("11110"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].level", contains("EMD", "EMD")))
                .andExpect(jsonPath("$[*].code", contains("11110530", "11110540")))
                .andExpect(jsonPath("$[*].name", contains("사직동", "삼청동")));

        zones(regionId("11110540"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("삼청동 1구역", "삼청동 2구역")))
                .andExpect(jsonPath("$[0].dongId").value(regionId("11110540")));
    }

    @Test
    @DisplayName("FNC-C-008 지역 목록 항목은 api 명세의 필드를 갖고 경계는 GeoJSON 객체, 중심점은 DB 값이다")
    void regions_item_hasSpecFieldsWithBoundaryObject() throws Exception {
        BigDecimal centerLat = jdbcTemplate.queryForObject(
                "SELECT center_lat FROM region WHERE code = '11110540'", BigDecimal.class);
        BigDecimal centerLng = jdbcTemplate.queryForObject(
                "SELECT center_lng FROM region WHERE code = '11110540'", BigDecimal.class);

        regions(regionId("11110"))
                .andExpect(jsonPath("$[1].id").value(regionId("11110540")))
                .andExpect(jsonPath("$[1].centerLat").value(closeTo(centerLat.doubleValue(), 1e-9)))
                .andExpect(jsonPath("$[1].centerLng").value(closeTo(centerLng.doubleValue(), 1e-9)))
                // 문자열이 아니라 JSON 객체여야 jsonPath로 안쪽을 읽을 수 있다
                .andExpect(jsonPath("$[1].boundary.type").value("Polygon"))
                .andExpect(jsonPath("$[1].boundary.coordinates[0][0][0]").value(127.0))
                .andExpect(jsonPath("$[1].boundary.coordinates[0][0][1]").value(37.55))
                .andExpect(jsonPath("$[1].parentId").doesNotExist());
    }

    @Test
    @DisplayName("FNC-C-008 시·도, 시·군·구 경계도 객체로 내려가고 소속 읍·면·동 경계를 합친 값이다")
    void regions_upperLevelBoundary_isObject() throws Exception {
        regions(null)
                .andExpect(jsonPath("$[0].boundary.type").isString())
                .andExpect(jsonPath("$[0].boundary.coordinates").isArray());
        regions(regionId("11"))
                .andExpect(jsonPath("$[0].boundary.type").isString())
                .andExpect(jsonPath("$[0].boundary.coordinates").isArray());
    }

    @Test
    @DisplayName("FNC-C-008 읍·면·동을 parentId로 주면 빈 목록(200)")
    void regions_parentIsEmd_empty() throws Exception {
        regions(regionId("11110540")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-C-008 없는 parentId는 404 REGION_NOT_FOUND")
    void regions_unknownParent_404() throws Exception {
        regions(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("FNC-C-008 parentId가 숫자가 아니면 400 INVALID_INPUT")
    void regions_parentNotNumber_400() throws Exception {
        mockMvc.perform(get(REGIONS_URL).param("parentId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-C-008 dongId가 없으면 삭제되지 않은 모든 구역을 번호 순으로 준다")
    void zones_withoutDong_allActiveInIdOrder() throws Exception {
        zones(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", contains((int) samcheongZoneA, (int) samcheongZoneB, (int) sogongZone)));
    }

    @Test
    @DisplayName("FNC-C-008 구역 항목은 api 명세의 필드를 갖고 경계는 객체, 면적·중심점이 맞다")
    void zones_item_hasSpecFieldsWithBoundaryObject() throws Exception {
        BigDecimal areaM2 = jdbcTemplate.queryForObject("SELECT area_m2 FROM zone WHERE id = ?", BigDecimal.class, samcheongZoneA);
        BigDecimal centerLat = jdbcTemplate.queryForObject("SELECT center_lat FROM zone WHERE id = ?", BigDecimal.class, samcheongZoneA);
        BigDecimal centerLng = jdbcTemplate.queryForObject("SELECT center_lng FROM zone WHERE id = ?", BigDecimal.class, samcheongZoneA);

        zones(regionId("11110540"))
                .andExpect(jsonPath("$[0].id").value(samcheongZoneA))
                .andExpect(jsonPath("$[0].name").value("삼청동 1구역"))
                .andExpect(jsonPath("$[0].areaM2").value(closeTo(areaM2.doubleValue(), 1e-6)))
                .andExpect(jsonPath("$[0].centerLat").value(closeTo(centerLat.doubleValue(), 1e-9)))
                .andExpect(jsonPath("$[0].centerLng").value(closeTo(centerLng.doubleValue(), 1e-9)))
                .andExpect(jsonPath("$[0].boundary.type").value("Polygon"))
                .andExpect(jsonPath("$[0].boundary.coordinates[0][0][0]").value(127.001))
                .andExpect(jsonPath("$[0].boundary.coordinates[0][0][1]").value(37.601))
                .andExpect(jsonPath("$[0].hasCamera").value(false));
    }

    @Test
    @DisplayName("FNC-C-008 dongId를 주면 그 동의 구역만 준다")
    void zones_byDong_onlyThatDong() throws Exception {
        zones(regionId("11140520"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(sogongZone));
        zones(regionId("11110530")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-C-008 삭제된 구역은 전체 목록과 동별 목록 모두에서 빠진다(loca-005)")
    void zones_deletedExcluded() throws Exception {
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", samcheongZoneA);

        zones(null)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) samcheongZoneA))));
        zones(regionId("11110540"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(samcheongZoneB));
    }

    @Test
    @DisplayName("FNC-C-008 hasCamera는 삭제되지 않은 캠이 있을 때만 true")
    void zones_hasCamera_onlyForActiveCamera() throws Exception {
        createCamera(samcheongZoneA, "삼청동 캠");
        long deletedCamera = createCamera(sogongZone, "삭제될 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedCamera);

        zones(null)
                .andExpect(jsonPath("$[0].hasCamera").value(true))
                .andExpect(jsonPath("$[1].hasCamera").value(false))
                .andExpect(jsonPath("$[2].hasCamera").value(false));
        zones(regionId("11110540"))
                .andExpect(jsonPath("$[0].hasCamera").value(true))
                .andExpect(jsonPath("$[1].hasCamera").value(false));
    }

    @Test
    @DisplayName("FNC-C-008 없는 dongId는 404 REGION_NOT_FOUND")
    void zones_unknownDong_404() throws Exception {
        zones(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("FNC-C-008 dongId가 읍·면·동이 아닌 지역이어도 있으면 200 빈 목록")
    void zones_dongIdOfSigungu_emptyOk() throws Exception {
        zones(regionId("11110")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-C-008 구역이 하나도 없으면 빈 목록")
    void zones_noZones_empty() throws Exception {
        jdbcTemplate.update("DELETE FROM zone");

        zones(null).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    private long createZone(String name, String polygon) {
        return zoneRegisterService.register(name, jsonMapper.readTree(polygon)).id();
    }

    private long createCamera(long zoneId, String name) {
        return cameraRegisterService
                .register(new CameraCreateRequest(zoneId, name, CameraSourceType.LIVE, LIVE_URI))
                .id();
    }

    private Long regionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
    }

    private ResultActions regions(Long parentId) throws Exception {
        return parentId == null
                ? mockMvc.perform(get(REGIONS_URL))
                : mockMvc.perform(get(REGIONS_URL).param("parentId", String.valueOf(parentId)));
    }

    private ResultActions zones(Long dongId) throws Exception {
        return dongId == null
                ? mockMvc.perform(get(ZONES_URL))
                : mockMvc.perform(get(ZONES_URL).param("dongId", String.valueOf(dongId)));
    }
}
