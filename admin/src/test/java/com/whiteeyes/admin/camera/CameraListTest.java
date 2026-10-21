package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@Import(AdminTestUserConfig.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class CameraListTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String CAMERAS_URL = "/api/v1/manage/cameras";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";

    // 삼청동(종로구) / 소공동(중구) / 사직동(종로구) 안의 사각형
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final String SAJIK_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[126.96,37.56],[126.962,37.56],[126.962,37.562],[126.96,37.562],[126.96,37.56]]]}";

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private ZoneRegisterService zoneRegisterService;

    @Autowired
    private CameraRegisterService cameraRegisterService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long samcheongZoneId;
    private long sogongZoneId;
    private long sajikZoneId;

    @BeforeEach
    void prepareZones() {
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZoneId = createZone("삼청동 1구역", SAMCHEONG_SQUARE);
        sogongZoneId = createZone("소공동 1구역", SOGONG_SQUARE);
        sajikZoneId = createZone("사직동 1구역", SAJIK_SQUARE);
    }

    // camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-022 캠이 없으면 빈 목록")
    void list_noCameras_empty() throws Exception {
        list(null).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-A-022 캠 정보와 구역 이름·지역 경로·면적을 함께 돌려준다")
    void list_returnsCameraWithZoneAndRegionInfo() throws Exception {
        long cameraId = createCamera(samcheongZoneId, "삼청동 캠");

        list(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(cameraId))
                .andExpect(jsonPath("$[0].zoneId").value(samcheongZoneId))
                .andExpect(jsonPath("$[0].zoneName").value("삼청동 1구역"))
                .andExpect(jsonPath("$[0].regionPath").value("종로구 · 삼청동"))
                .andExpect(jsonPath("$[0].areaM2").isNumber())
                .andExpect(jsonPath("$[0].name").value("삼청동 캠"))
                .andExpect(jsonPath("$[0].sourceType").value("LIVE"))
                .andExpect(jsonPath("$[0].sourceUri").value(LIVE_URI))
                .andExpect(jsonPath("$[0].status").value("STOPPED"))
                .andExpect(jsonPath("$[0].errorMessage").value(nullValue()))
                .andExpect(jsonPath("$[0].runRequested").value(false));
    }

    @Test
    @DisplayName("FNC-A-022 준비 중인 캠만 진행률을 돌려주고, 오류 내용도 보인다")
    void list_progressOnlyWhilePreparing_andErrorMessage() throws Exception {
        long preparing = createCamera(samcheongZoneId, "준비 캠");
        long running = createCamera(sogongZoneId, "동작 캠");
        long error = createCamera(sajikZoneId, "오류 캠");
        jdbcTemplate.update("UPDATE camera SET status = 'PREPARING', progress = 40 WHERE id = ?", preparing);
        jdbcTemplate.update("UPDATE camera SET status = 'RUNNING', progress = 100 WHERE id = ?", running);
        jdbcTemplate.update(
                "UPDATE camera SET status = 'ERROR', progress = 0, error_message = '파일 없음' WHERE id = ?", error);

        list(null)
                .andExpect(jsonPath("$[0].status").value("PREPARING"))
                .andExpect(jsonPath("$[0].progress").value(40))
                .andExpect(jsonPath("$[1].status").value("RUNNING"))
                .andExpect(jsonPath("$[1].progress").value(nullValue()))
                .andExpect(jsonPath("$[2].status").value("ERROR"))
                .andExpect(jsonPath("$[2].progress").value(nullValue()))
                .andExpect(jsonPath("$[2].errorMessage").value("파일 없음"));
    }

    @Test
    @DisplayName("FNC-A-022 삭제된 캠은 목록에서 빠지고 캠 번호 순으로 정렬한다")
    void list_excludesDeleted_andOrdersById() throws Exception {
        long first = createCamera(samcheongZoneId, "첫 캠");
        long deleted = createCamera(sogongZoneId, "삭제될 캠");
        long third = createCamera(sajikZoneId, "셋째 캠");
        jdbcTemplate.update("UPDATE camera SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);

        list(null)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", contains((int) first, (int) third)));
    }

    @Test
    @DisplayName("FNC-A-022 통계·이벤트는 아직 연결 전이라 stat은 null, todayEventCount는 0, recent10m은 빈 목록")
    void list_statAndEventsNotYetConnected() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");

        list(null)
                .andExpect(jsonPath("$[0].stat").value(nullValue()))
                .andExpect(jsonPath("$[0].todayEventCount").value(0))
                .andExpect(jsonPath("$[0].recent10m", empty()));
    }

    @Test
    @DisplayName("FNC-A-022 regionId가 시·군·구면 그 아래 구역의 캠만 돌려준다")
    void list_filterBySigungu() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");

        list(regionId("11110")) // 종로구
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("삼청동 캠", "사직동 캠")));
        list(regionId("11140")) // 중구
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("소공동 캠"));
    }

    @Test
    @DisplayName("FNC-A-022 regionId가 읍·면·동이면 그 동의 캠만, 시·도면 아래 모든 캠을 돌려준다")
    void list_filterByEmdAndSido() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");

        list(regionId("11110540")) // 삼청동
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("삼청동 캠"));
        list(regionId("11")) // 서울특별시
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    @DisplayName("FNC-A-022 없는 regionId나 캠이 없는 지역이면 빈 목록")
    void list_filterNoMatch_empty() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");

        list(999_999L).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        list(regionId("11140520")) // 소공동(캠 없음)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("FNC-A-022 캠이 늘어도 쿼리 수가 늘지 않는다(캠마다 따로 조회하지 않음)")
    void list_queryCountDoesNotGrowWithCameraCount() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        long withOneCamera = countStatementsOfList();

        createCamera(sogongZoneId, "소공동 캠");
        createCamera(sajikZoneId, "사직동 캠");
        long withThreeCameras = countStatementsOfList();

        // 통계가 꺼져 있어 0 == 0으로 통과하는 일이 없도록 쿼리가 실제로 세어졌는지도 본다
        assertThat(withOneCamera).isPositive();
        assertThat(withThreeCameras).isEqualTo(withOneCamera);
    }

    private long countStatementsOfList() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        list(null).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
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

    private ResultActions list(Long regionId) throws Exception {
        return regionId == null
                ? mockMvc.perform(get(CAMERAS_URL))
                : mockMvc.perform(get(CAMERAS_URL).param("regionId", String.valueOf(regionId)));
    }
}
