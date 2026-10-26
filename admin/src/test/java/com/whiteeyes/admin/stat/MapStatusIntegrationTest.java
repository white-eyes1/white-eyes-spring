package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

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

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;

// 테스트 행정구역 파일: 서울(11) → 종로구(11110: 사직동 11110530, 삼청동 11110540), 중구(11140: 소공동 11140520)
// 통계 시각은 실제 현재 시각 기준이다(컨트롤러가 현재 시각으로 '최근 5분'을 판정)
/** 혼잡 단계 색 표시(FNC-C-009). 로그인한 사용자로 요청한다(접근 권한 검사, FNC-C-003). */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class MapStatusIntegrationTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String STATUS_URL = "/api/v1/map/status";
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private LocalDateTime latestMinute;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        latestMinute = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
                .truncatedTo(ChronoUnit.MINUTES)
                .minusMinutes(1);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 이 테스트가 넣은 통계와 구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-C-009 단계 경계값 바로 아래·위에서 색(단계)이 바뀐다")
    void zoneLevel_aroundWarnThreshold_changes() throws Exception {
        long below = insertZone("11110540", "삼청동 1구역", "100.00");
        long at = insertZone("11110540", "삼청동 2구역", "100.00");
        insertStat(below, latestMinute, "69.99", "0.6999");
        insertStat(at, latestMinute, "70.00", "0.7000");

        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11110540")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitType").value("ZONE"))
                .andExpect(jsonPath("$.items[0].id").value(below))
                .andExpect(jsonPath("$.items[0].level").value(1))
                .andExpect(jsonPath("$.items[1].id").value(at))
                .andExpect(jsonPath("$.items[1].level").value(2))
                .andExpect(jsonPath("$.items[1].density").value(0.7))
                .andExpect(jsonPath("$.items[1].centerLat").value(37.6))
                .andExpect(jsonPath("$.items[1].statAt").value(kst(latestMinute)))
                .andExpect(jsonPath("$.items[1].todayEventCount").value(0))
                .andExpect(jsonPath("$.items[1].ongoingEvent").value(false));
    }

    @Test
    @DisplayName("FNC-C-009 시·도의 CHILD는 시·군·구별로 가장 늦은 1분의 구역 값만 합산한다")
    void childOfSido_sumsZonesAtLatestMinute() throws Exception {
        long sajik = insertZone("11110530", "사직동 1구역", "100.00");
        long samcheong = insertZone("11110540", "삼청동 1구역", "300.00");
        long lagging = insertZone("11110540", "삼청동 2구역", "1000.00");
        insertStat(sajik, latestMinute, "50.00", "0.5000");
        insertStat(samcheong, latestMinute, "30.00", "0.1000");
        // 캠이 끊겨 1분 전 통계만 있는 구역은 합산에서 빠진다(인원·면적 모두)
        insertStat(lagging, latestMinute.minusMinutes(1), "900.00", "0.9000");

        String body = mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitType").value("SIGUNGU"))
                .andExpect(jsonPath("$.items[0].name").value("종로구"))
                // (50 + 30) ÷ (100 + 300) = 0.2
                .andExpect(jsonPath("$.items[0].countAvg").value(80.0))
                .andExpect(jsonPath("$.items[0].density").value(0.2))
                .andExpect(jsonPath("$.items[0].level").value(1))
                .andExpect(jsonPath("$.items[0].statAt").value(kst(latestMinute)))
                .andExpect(jsonPath("$.items[0].centerLat").doesNotExist())
                .andExpect(jsonPath("$.items[1].name").value("중구"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 구역이 없는 중구는 목록에 남고 통계 값만 null('데이터 없음')
        assertThat(body).contains("\"name\":\"중구\",\"countAvg\":null,\"density\":null,\"level\":null,\"statAt\":null");
    }

    @Test
    @DisplayName("FNC-C-009 시·군·구의 CHILD는 읍·면·동 단위, ZONE은 모든 구역(삭제된 구역 제외)")
    void childOfSigunguAndZoneUnit() throws Exception {
        long sajik = insertZone("11110530", "사직동 1구역", "100.00");
        long deleted = insertZone("11110540", "삼청동 삭제된 구역", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);
        insertStat(sajik, latestMinute, "50.00", "0.5000");
        insertStat(deleted, latestMinute, "500.00", "5.0000");

        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11110")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitType").value("EMD"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("사직동"))
                .andExpect(jsonPath("$.items[0].density").value(0.5))
                .andExpect(jsonPath("$.items[1].name").value("삼청동"))
                .andExpect(jsonPath("$.items[1].level").isEmpty());

        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")).param("unit", "ZONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitType").value("ZONE"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sajik));
    }

    @Test
    @DisplayName("FNC-C-009 최근 5분 안에 통계가 없으면 데이터 없음(level 등 null)")
    void staleStat_isNoData() throws Exception {
        long zone = insertZone("11110540", "삼청동 1구역", "100.00");
        insertStat(zone, latestMinute.minusMinutes(6), "90.00", "0.9000");

        String body = mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11110540")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(zone))
                .andExpect(jsonPath("$.items[0].centerLat").value(37.6))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"countAvg\":null,\"density\":null,\"level\":null,\"statAt\":null");
    }

    @Test
    @DisplayName("FNC-C-009 없는 지역은 404, unit 형식 오류와 regionId 없음은 400")
    void errors() throws Exception {
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")).param("unit", "DONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("FNC-C-017 단위별 오늘 이벤트 수: 혼잡·급증만, 캠 이상·어제 제외, 지역은 삭제된 구역까지 합산")
    void todayEventCount_perUnit() throws Exception {
        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay();
        long sajik = insertZone("11110530", "사직동 1구역", "100.00");
        long samcheong = insertZone("11110540", "삼청동 1구역", "100.00");
        long deleted = insertZone("11110540", "삼청동 삭제된 구역", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);
        insertEvent(sajik, "CONGESTION", todayStart.plusSeconds(1));
        insertEvent(sajik, "SURGE", todayStart.plusSeconds(2));
        insertEvent(sajik, "CAM_FAULT", todayStart.plusSeconds(3));
        insertEvent(sajik, "CONGESTION", todayStart.minusSeconds(1));
        insertEvent(samcheong, "SURGE", todayStart.plusSeconds(4));
        insertEvent(deleted, "CONGESTION", todayStart.plusSeconds(5));

        // 시·도: 종로구 = 2 + 1 + 1(삭제된 구역), 중구 = 0
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("종로구"))
                .andExpect(jsonPath("$.items[0].todayEventCount").value(4))
                .andExpect(jsonPath("$.items[1].name").value("중구"))
                .andExpect(jsonPath("$.items[1].todayEventCount").value(0));
        // 시·군·구: 사직동 2, 삼청동 1 + 1(삭제된 구역)
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11110")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("사직동"))
                .andExpect(jsonPath("$.items[0].todayEventCount").value(2))
                .andExpect(jsonPath("$.items[1].name").value("삼청동"))
                .andExpect(jsonPath("$.items[1].todayEventCount").value(2));
        // 구역 단위: 삭제된 구역은 목록에서 빠지고 구역마다 자기 것만 센다(통계가 없어도 센다)
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")).param("unit", "ZONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(sajik))
                .andExpect(jsonPath("$.items[0].todayEventCount").value(2))
                .andExpect(jsonPath("$.items[1].id").value(samcheong))
                .andExpect(jsonPath("$.items[1].todayEventCount").value(1));
    }

    @Test
    @DisplayName("FNC-C-009 진행 중 이벤트: 열린 혼잡·급증이 있는 구역·지역만 true, 종료·캠 이상·삭제된 구역은 false")
    void ongoingEvent_perUnit() throws Exception {
        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay();
        long open = insertZone("11110530", "사직동 열림", "100.00");
        long surge = insertZone("11110530", "사직동 급증", "100.00");
        long closed = insertZone("11110540", "삼청동 종료", "100.00");
        long camFault = insertZone("11110540", "삼청동 캠 이상", "100.00");
        long deleted = insertZone("11140520", "소공동 삭제", "100.00");
        long noStat = insertZone("11110530", "사직동 통계 없음", "100.00");
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);
        insertStat(open, latestMinute, "10.00", "0.1000");
        insertEvent(open, "CONGESTION", todayStart.plusSeconds(1));
        insertEvent(surge, "SURGE", todayStart.plusSeconds(2));
        insertEvent(noStat, "CONGESTION", todayStart.plusSeconds(3));
        insertEvent(closed, "CONGESTION", todayStart.plusSeconds(4));
        jdbcTemplate.update(
                "UPDATE event SET ended_at = ? WHERE zone_id = ?",
                Timestamp.valueOf(todayStart.plusMinutes(5)),
                closed);
        insertEvent(camFault, "CAM_FAULT", todayStart.plusSeconds(5));
        insertEvent(deleted, "CONGESTION", todayStart.plusSeconds(6));
        // 조치완료여도 아직 닫히지 않았으면 진행 중이다(처리 상태는 보지 않는다)
        jdbcTemplate.update("UPDATE event SET status = 'RESOLVED' WHERE zone_id = ?", surge);

        // 구역 단위: 열린 혼잡·급증은 true(통계가 없어도), 종료·캠 이상은 false. 삭제된 구역은 목록에 없다
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")).param("unit", "ZONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id==" + open + ")].ongoingEvent").value(true))
                .andExpect(jsonPath("$.items[?(@.id==" + surge + ")].ongoingEvent").value(true))
                .andExpect(jsonPath("$.items[?(@.id==" + noStat + ")].ongoingEvent").value(true))
                // 핀 배지 숫자는 같은 항목의 todayEventCount다
                .andExpect(jsonPath("$.items[?(@.id==" + open + ")].todayEventCount").value(1))
                .andExpect(jsonPath("$.items[?(@.id==" + surge + ")].todayEventCount").value(1))
                .andExpect(jsonPath("$.items[?(@.id==" + closed + ")].ongoingEvent").value(false))
                .andExpect(jsonPath("$.items[?(@.id==" + camFault + ")].ongoingEvent").value(false))
                .andExpect(jsonPath("$.items[?(@.id==" + deleted + ")]").isEmpty());
        // 시·도: 종로구는 소속 구역에 열린 이벤트가 있어 true, 중구는 삭제된 구역의 열린 이벤트만 있어 false
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("종로구"))
                .andExpect(jsonPath("$.items[0].ongoingEvent").value(true))
                .andExpect(jsonPath("$.items[1].name").value("중구"))
                .andExpect(jsonPath("$.items[1].ongoingEvent").value(false));
        // 시·군·구: 사직동은 true, 삼청동은 종료·캠 이상뿐이라 false
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11110")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("사직동"))
                .andExpect(jsonPath("$.items[0].ongoingEvent").value(true))
                .andExpect(jsonPath("$.items[1].name").value("삼청동"))
                .andExpect(jsonPath("$.items[1].ongoingEvent").value(false));
    }

    @Test
    @DisplayName("FNC-C-009 진행 중 이벤트: 어제 시작해 자정을 넘겨 열려 있으면 ongoingEvent true, todayEventCount 0")
    void ongoingEvent_openSinceYesterday_trueWithZeroTodayCount() throws Exception {
        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay();
        long zone = insertZone("11110530", "사직동 어제부터 열림", "100.00");
        insertEvent(zone, "CONGESTION", todayStart.minusHours(1));

        // 배지는 붙지만(true) 오늘 0시 이후 시작한 이벤트가 없어 숫자는 0이다. 화면은 0이면 숫자 없는 배지를 그린다
        mockMvc.perform(get(STATUS_URL).with(AdminTestLogin.admin()).param("regionId", regionId("11")).param("unit", "ZONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id==" + zone + ")].ongoingEvent").value(true))
                .andExpect(jsonPath("$.items[?(@.id==" + zone + ")].todayEventCount").value(0));
    }

    private void insertEvent(long zoneId, String type, LocalDateTime startedAt) {
        jdbcTemplate.update(
                "INSERT INTO event (type, zone_id, started_at, threshold_snapshot) VALUES (?, ?, ?, ?)",
                type,
                zoneId,
                Timestamp.valueOf(startedAt),
                "{\"warnDensity\":0.7,\"dangerDensity\":1.5}");
    }

    private String regionId(String code) {
        return String.valueOf(jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code));
    }

    // 구역 등록 API로 만들면 면적이 다각형에서 계산되므로, 계산하기 쉬운 면적을 두려고 직접 넣는다
    private long insertZone(String dongCode, String name, String areaM2) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, ?, '{}', 37.600000, 127.002000)",
                Long.valueOf(regionId(dongCode)),
                name,
                new BigDecimal(areaM2));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private void insertStat(long zoneId, LocalDateTime statAt, String countAvg, String density) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec)"
                        + " VALUES (?, ?, ?, 0, 0, ?, 60)",
                zoneId,
                Timestamp.valueOf(statAt),
                new BigDecimal(countAvg),
                new BigDecimal(density));
    }

    private String kst(LocalDateTime time) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.atOffset(KST_OFFSET));
    }
}
