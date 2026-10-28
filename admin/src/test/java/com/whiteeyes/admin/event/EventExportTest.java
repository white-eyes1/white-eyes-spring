package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.support.AdminTestLogin;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 이벤트 엑셀 다운로드(FNC-A-039) GET /api/v1/events/export. 이벤트 행은 SQL로 직접 넣는다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class EventExportTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String EXPORT_URL = "/api/v1/events/export";
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String LIVE_URI = "rtsp://192.168.0.10:554/stream1";
    private static final int MAX_ROWS = 50_000;

    // 삼청동(종로구) / 소공동(중구) / 사직동(종로구) 안의 사각형
    private static final String SAMCHEONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.001,37.601],[127.003,37.601],[127.003,37.603],[127.001,37.603],[127.001,37.601]]]}";
    private static final String SOGONG_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[127.06,37.56],[127.062,37.56],[127.062,37.562],[127.06,37.562],[127.06,37.56]]]}";
    private static final String SAJIK_SQUARE =
            "{\"type\":\"Polygon\",\"coordinates\":[[[126.96,37.56],[126.962,37.56],[126.962,37.562],[126.96,37.562],[126.96,37.56]]]}";

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 12, 9, 0);

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
    void prepare() {
        AdminTestLogin.loginForDirectCalls();
        clearAll();
        regionLoadService.load(REGION_FILE);
        samcheongZoneId = createZone("삼청동 1구역", SAMCHEONG_SQUARE);
        sogongZoneId = createZone("소공동 1구역", SOGONG_SQUARE);
        sajikZoneId = createZone("사직동 1구역", SAJIK_SQUARE);
        // 구역 등록이 남긴 작업 기록은 이 시험의 대상이 아니다
        jdbcTemplate.update("DELETE FROM work_history");
    }

    // event → camera → zone → region 순으로 외래키가 걸려 있어, 남겨 두면 다른 테스트가 region을 지우다 실패한다
    @AfterEach
    void clearAfter() {
        AdminTestLogin.clear();
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    @Test
    @DisplayName("FNC-A-039 응답은 xlsx 파일이고 이름은 events_{from}_{to}.xlsx(to는 마지막으로 포함된 날)")
    void export_responseIsXlsxWithFileName() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, T0, T0.plusMinutes(10), 3, "1.6400", 48, "UNCHECKED");

        MvcResult result = export(
                        param("from", "2026-10-12T00:00:00+09:00"), param("to", "2026-10-13T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).isEqualTo(XLSX_TYPE);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("events_20261012_20261012.xlsx");
        assertThat(rowsOf(result)).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-039 기간을 비우면 파일 이름의 그 칸은 all")
    void export_noPeriod_fileNameUsesAll() throws Exception {
        MvcResult result = export().andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("events_all_all.xlsx");
        assertThat(rowsOf(result)).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-039 이벤트가 없으면 머리글만 있는 파일")
    void export_noEvents_headerOnly() throws Exception {
        MvcResult result = export().andExpect(status().isOk()).andReturn();

        Sheet sheet = sheetOf(result);
        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("발생 시각");
        assertThat(sheet.getLastRowNum()).isZero();
    }

    @Test
    @DisplayName("FNC-A-039 발생 시각 내림차순으로 모든 이벤트가 들어간다(목록 API와 같은 순서)")
    void export_allEvents_latestFirst() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, T0, T0.plusMinutes(5), 2, "0.8000", 10, "UNCHECKED");
        insertEvent("SURGE", sogongZoneId, T0.plusHours(2), null, null, "70.0000", 20, "CHECKED");
        insertEvent("CONGESTION", sajikZoneId, T0.plusHours(1), T0.plusHours(1).plusMinutes(5), 3, "1.6000", 40, "RESOLVED");

        List<Row> rows = rowsOf(export().andExpect(status().isOk()).andReturn());

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0.plusHours(2));
        assertThat(rows.get(1).getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0.plusHours(1));
        assertThat(rows.get(2).getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0);
    }

    @Test
    @DisplayName("FNC-A-039 기간은 from 이상 to 미만으로 거른다")
    void export_period_halfOpen() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, T0.minusMinutes(1), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, T0, null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, T0.plusHours(1), null, 2, "0.8000", 10, "UNCHECKED");

        List<Row> rows = rowsOf(export(param("from", "2026-10-12T09:00:00+09:00"), param("to", "2026-10-12T10:00:00+09:00"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCell(0).getLocalDateTimeCellValue()).isEqualTo(T0);
    }

    @Test
    @DisplayName("FNC-A-039 overlap=true면 기간과 겹치는 이벤트를 포함하고, 목록과 같은 건수가 나온다(#310)")
    void export_overlap_sameAsList() throws Exception {
        // 기간 09:00~10:00. 발생이 앞이어도 기간 안에서 이어진 이벤트(종료가 from 뒤이거나 진행 중)는 겹침
        insertEvent("CONGESTION", samcheongZoneId, T0.minusMinutes(30), T0.plusMinutes(10), 2, "0.8000", 10, "RESOLVED");
        insertEvent("CONGESTION", samcheongZoneId, T0.minusMinutes(30), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, T0.minusHours(2), T0.minusHours(1), 2, "0.8000", 10, "RESOLVED");
        insertEvent("CONGESTION", samcheongZoneId, T0.plusMinutes(30), null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("CONGESTION", samcheongZoneId, T0.plusHours(2), null, 2, "0.8000", 10, "UNCHECKED");
        ParamPair from = param("from", "2026-10-12T09:00:00+09:00");
        ParamPair to = param("to", "2026-10-12T10:00:00+09:00");

        int exported = rowCount(from, to, param("overlap", "true"));

        assertThat(exported).isEqualTo(3);
        mockMvc.perform(get("/api/v1/events")
                        .with(AdminTestLogin.admin())
                        .param("from", from.value())
                        .param("to", to.value())
                        .param("overlap", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(exported));
        // 비우면 기본(false)이라 발생 시각 기준으로 거른다
        assertThat(rowCount(from, to)).isEqualTo(1);
        assertThat(rowCount(from, to, param("overlap", "false"))).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-039 종류·처리 상태·구역·지역·검색어 조건을 목록과 같이 적용한다")
    void export_filters_sameAsList() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, T0, null, 2, "0.8000", 10, "UNCHECKED");
        insertEvent("SURGE", samcheongZoneId, T0.plusMinutes(1), null, null, "70.0000", 20, "CHECKED");
        insertEvent("CONGESTION", sogongZoneId, T0.plusMinutes(2), null, 3, "1.6000", 40, "RESOLVED");
        insertEvent("CONGESTION", sajikZoneId, T0.plusMinutes(3), null, 2, "0.9000", 15, "UNCHECKED");

        assertThat(rowCount(param("types", "SURGE"))).isEqualTo(1);
        assertThat(rowCount(param("types", "SURGE"), param("types", "CONGESTION"))).isEqualTo(4);
        assertThat(rowCount(param("statuses", "UNCHECKED"))).isEqualTo(2);
        assertThat(rowCount(param("zoneId", String.valueOf(sogongZoneId)))).isEqualTo(1);
        assertThat(rowCount(param("regionId", String.valueOf(regionId("종로구"))))).isEqualTo(3);
        assertThat(rowCount(param("keyword", "소공"))).isEqualTo(1);
        assertThat(rowCount(param("types", "CONGESTION"), param("statuses", "UNCHECKED"), param("keyword", "사직")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-039 page·size는 무시하고 조건에 맞는 전부를 내려준다")
    void export_ignoresPaging() throws Exception {
        for (int i = 0; i < 25; i++) {
            insertEvent("CONGESTION", samcheongZoneId, T0.plusMinutes(i), null, 2, "0.8000", 10, "UNCHECKED");
        }

        assertThat(rowCount(param("page", "1"), param("size", "5"))).isEqualTo(25);
    }

    @Test
    @DisplayName("FNC-A-039 열 값: 구역·지역·단계·처리 정보(처리자 아이디 포함)")
    void export_rowValues() throws Exception {
        long adminId = jdbcTemplate.queryForObject("SELECT id FROM admin_account LIMIT 1", Long.class);
        String adminLoginId = jdbcTemplate.queryForObject("SELECT login_id FROM admin_account WHERE id = ?", String.class, adminId);
        long eventId = insertEvent("CONGESTION", samcheongZoneId, T0, T0.plusMinutes(10), 3, "1.6400", 48, "RESOLVED");
        jdbcTemplate.update(
                "UPDATE event SET action_code = 'STAFF', action_note = '인력 2명 배치', handled_by = ?, handled_at = ? WHERE id = ?",
                adminId,
                T0.plusMinutes(20),
                eventId);
        jdbcTemplate.update("UPDATE zone SET deleted_at = NOW() WHERE id = ?", sajikZoneId);
        insertEvent("CONGESTION", sajikZoneId, T0.minusHours(1), null, 2, "0.8000", 10, "UNCHECKED");

        List<Row> rows = rowsOf(export().andExpect(status().isOk()).andReturn());

        Row handled = rows.get(0);
        assertThat(handled.getCell(2).getNumericCellValue()).isEqualTo(10.0);
        assertThat(handled.getCell(3).getStringCellValue()).isEqualTo("혼잡");
        assertThat(handled.getCell(4).getStringCellValue()).isEqualTo("서울특별시");
        assertThat(handled.getCell(5).getStringCellValue()).isEqualTo("종로구");
        assertThat(handled.getCell(6).getStringCellValue()).isEqualTo("삼청동");
        assertThat(handled.getCell(7).getStringCellValue()).isEqualTo("삼청동 1구역");
        assertThat(handled.getCell(8).getStringCellValue()).isEqualTo("위험");
        assertThat(handled.getCell(9).getNumericCellValue()).isEqualTo(1.64);
        assertThat(handled.getCell(11).getStringCellValue()).isEqualTo("조치완료");
        assertThat(handled.getCell(12).getStringCellValue()).isEqualTo("인력 배치");
        assertThat(handled.getCell(13).getStringCellValue()).isEqualTo("인력 2명 배치");
        assertThat(handled.getCell(14).getStringCellValue()).isEqualTo(adminLoginId);
        assertThat(handled.getCell(15).getLocalDateTimeCellValue()).isEqualTo(T0.plusMinutes(20));
        Row deleted = rows.get(1);
        assertThat(deleted.getCell(7).getStringCellValue()).isEqualTo("(삭제됨) 사직동 1구역");
        assertThat(deleted.getCell(1).getStringCellValue()).isEqualTo("진행 중");
    }

    @Test
    @DisplayName("FNC-A-039 캠 이상 행에는 최고 값이 없다")
    void export_camFaultRow() throws Exception {
        long cameraId = createCamera(samcheongZoneId, "삼청동 캠");
        jdbcTemplate.update(
                "INSERT INTO event (type, zone_id, camera_id, started_at, threshold_snapshot, status) VALUES ('CAM_FAULT', ?, ?, ?, '{}', 'UNCHECKED')",
                samcheongZoneId,
                cameraId,
                T0);

        Row row = rowsOf(export().andExpect(status().isOk()).andReturn()).get(0);

        assertThat(row.getCell(3).getStringCellValue()).isEqualTo("캠 이상");
        assertThat(row.getCell(8)).isNull();
        assertThat(row.getCell(9)).isNull();
        assertThat(row.getCell(10)).isNull();
    }

    @Test
    @DisplayName("FNC-A-039 내려받으면 EXCEL_DOWNLOAD / EVENT 작업 기록 1행(detail: kind=EVENT, 필터)")
    void export_recordsWorkHistory() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, T0, null, 2, "0.8000", 10, "UNCHECKED");

        export(param("from", "2026-10-12T00:00:00+09:00"), param("to", "2026-10-13T00:00:00+09:00"),
                        param("types", "CONGESTION"), param("keyword", "삼청"))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM work_history");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        assertThat(row.get("ACTION")).isEqualTo("EXCEL_DOWNLOAD");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("EVENT");
        assertThat(row.get("TARGET_ID")).isNull();
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("kind").asString()).isEqualTo("EVENT");
        JsonNode filter = detail.get("filter");
        assertThat(filter.get("from").asString()).isEqualTo("2026-10-12T00:00:00+09:00");
        assertThat(filter.get("to").asString()).isEqualTo("2026-10-13T00:00:00+09:00");
        assertThat(filter.get("types").get(0).asString()).isEqualTo("CONGESTION");
        assertThat(filter.get("keyword").asString()).isEqualTo("삼청");
    }

    @Test
    @DisplayName("FNC-A-039 작업 기록 detail의 필터에는 overlap=true일 때만 overlap이 남는다(#310)")
    void export_recordsOverlapOnlyWhenTrue() throws Exception {
        export(param("overlap", "true")).andExpect(status().isOk());
        export(param("overlap", "false")).andExpect(status().isOk());
        export().andExpect(status().isOk());

        List<byte[]> details = jdbcTemplate.queryForList("SELECT detail FROM work_history ORDER BY id", byte[].class);
        assertThat(details).hasSize(3);
        assertThat(filterOf(details.get(0)).get("overlap").asBoolean()).isTrue();
        assertThat(filterOf(details.get(1)).has("overlap")).isFalse();
        assertThat(filterOf(details.get(2)).has("overlap")).isFalse();
    }

    private JsonNode filterOf(byte[] detail) {
        return jsonMapper.readTree(new String(detail, StandardCharsets.UTF_8)).get("filter");
    }

    @Test
    @DisplayName("FNC-A-039 작업 기록의 대상 이름:구역 조건이면 구역 이름, 지역 조건이면 지역 이름, 없으면 '전체'")
    void export_recordsTargetName() throws Exception {
        insertEvent("CONGESTION", samcheongZoneId, T0, null, 2, "0.8000", 10, "UNCHECKED");

        export().andExpect(status().isOk());
        export(param("regionId", String.valueOf(regionId("종로구")))).andExpect(status().isOk());
        export(param("zoneId", String.valueOf(samcheongZoneId))).andExpect(status().isOk());

        List<String> names = jdbcTemplate.queryForList("SELECT target_name FROM work_history ORDER BY id", String.class);
        assertThat(names).containsExactly("전체", "종로구", "삼청동 1구역");
    }

    @Test
    @DisplayName("FNC-A-039 조건이 올바르지 않아 400이면 작업 기록이 남지 않는다")
    void export_invalidInput_noHistory() throws Exception {
        export(param("regionId", "1"), param("zoneId", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        export(param("from", "2026-10-13T00:00:00+09:00"), param("to", "2026-10-12T00:00:00+09:00"))
                .andExpect(status().isBadRequest());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-039 정확히 5만 건은 내려받을 수 있다")
    void export_exactlyMaxRows_ok() throws Exception {
        insertManyEvents(MAX_ROWS);

        MvcResult result = export().andExpect(status().isOk()).andReturn();

        // 5만 행을 POI로 통째로 읽으면 시험 JVM 메모리가 모자라 시트 XML의 행 태그만 센다(머리글 1행 포함)
        assertThat(countRowTags(result)).isEqualTo(MAX_ROWS + 1);
    }

    @Test
    @DisplayName("FNC-A-039 5만 건을 넘으면 400 TOO_MANY_ROWS이고 작업 기록은 남지 않는다")
    void export_overMaxRows_tooManyRows() throws Exception {
        insertManyEvents(MAX_ROWS + 1);

        export().andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-039 로그인하지 않으면 401, 일반 사용자 역할은 403이고 기록은 남지 않는다")
    void export_permissions() throws Exception {
        mockMvc.perform(get(EXPORT_URL).with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EXPORT_URL).with(user("someone").roles("USER"))).andExpect(status().isForbidden());

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-A-039 이벤트가 늘어도 한 번의 내려받기에 쓰는 SQL 수는 같다")
    void export_statementCount_independentOfEventCount() throws Exception {
        createCamera(samcheongZoneId, "삼청동 캠");
        insertEvent("CONGESTION", samcheongZoneId, T0, null, 2, "0.8000", 10, "UNCHECKED");
        long withOne = countStatements();

        for (int i = 1; i <= 9; i++) {
            insertEvent("CONGESTION", i % 2 == 0 ? sogongZoneId : sajikZoneId, T0.plusMinutes(i), null, 2, "0.8000", 10, "UNCHECKED");
        }
        long withTen = countStatements();

        assertThat(withTen).isEqualTo(withOne);
    }

    private long countStatements() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        export().andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private int rowCount(ParamPair... params) throws Exception {
        return rowsOf(export(params).andExpect(status().isOk()).andReturn()).size();
    }

    private ResultActions export(ParamPair... params) throws Exception {
        MockHttpServletRequestBuilder request = get(EXPORT_URL).with(AdminTestLogin.admin());
        for (ParamPair pair : params) {
            request.param(pair.name(), pair.value());
        }
        return mockMvc.perform(request);
    }

    private static Sheet sheetOf(MvcResult result) throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        return workbook.getSheetAt(0);
    }

    // 머리글 행을 뺀 데이터 행
    private static List<Row> rowsOf(MvcResult result) throws Exception {
        Sheet sheet = sheetOf(result);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            rows.add(sheet.getRow(i));
        }
        return rows;
    }

    private static int countRowTags(MvcResult result) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("event-export", ".xlsx");
        try {
            java.nio.file.Files.write(file, result.getResponse().getContentAsByteArray());
            try (ZipFile zip = new ZipFile(file.toFile())) {
                String xml = new String(
                        zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                        StandardCharsets.UTF_8);
                return xml.split("<row ", -1).length - 1;
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static ParamPair param(String name, String value) {
        return new ParamPair(name, value);
    }

    private record ParamPair(String name, String value) {}

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long regionId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = ?", Long.class, name);
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM event");
        jdbcTemplate.update("DELETE FROM zone_stat_minute");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    private long createZone(String name, String polygon) {
        // MockMvc 요청이 끝나면 현재 스레드의 로그인 정보가 비워지므로 부를 때마다 다시 채운다
        AdminTestLogin.loginForDirectCalls();
        return zoneRegisterService.register(name, jsonMapper.readTree(polygon)).id();
    }

    private long createCamera(long zoneId, String name) {
        AdminTestLogin.loginForDirectCalls();
        return cameraRegisterService
                .register(new CameraCreateRequest(zoneId, name, CameraSourceType.LIVE, LIVE_URI))
                .id();
    }

    // 5만 건 경계 시험용: 한 문장으로 행을 만든다(1초 간격)
    private void insertManyEvents(int rows) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, started_at, ended_at, peak_level, peak_value, peak_count,
                                   threshold_snapshot, status)
                SELECT 'CONGESTION', ?, DATEADD('SECOND', X, TIMESTAMP '2026-10-12 00:00:00'),
                       DATEADD('SECOND', X + 1, TIMESTAMP '2026-10-12 00:00:00'), 2, 0.8, 10, '{}', 'RESOLVED'
                FROM SYSTEM_RANGE(1, ?)
                """,
                samcheongZoneId,
                rows);
    }

    private long insertEvent(
            String type,
            long zoneId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Integer peakLevel,
            String peakValue,
            Integer peakCount,
            String status) {
        jdbcTemplate.update(
                """
                INSERT INTO event (type, zone_id, started_at, ended_at, peak_level, peak_value, peak_count,
                                   threshold_snapshot, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}', ?)
                """,
                type,
                zoneId,
                startedAt,
                endedAt,
                peakLevel,
                peakValue,
                peakCount,
                status);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM event", Long.class);
    }
}
