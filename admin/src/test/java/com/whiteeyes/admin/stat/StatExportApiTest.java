package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.support.AdminTestLogin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 통계 엑셀 다운로드(FNC-C-023) API: 파일 응답, 시연용 표시, 100만 행 제한, 작업 기록 저장 */
@SpringBootTest
@AutoConfigureMockMvc
class StatExportApiTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final String EXPORT_URL = "/api/v1/stats/export";
    private static final LocalDateTime H0 = LocalDateTime.of(2099, 1, 1, 10, 0);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long sajik;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        sajik = insertZone("11110530", "사직동 1구역");
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지우거나 기록 수를 셀 수 있게 넣은 행을 지우고 끝낸다
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-C-023 구역 엑셀: xlsx 파일, 파일 이름 stats_{대상}_{from}_{to}.xlsx, 항목별 시트, 시연용 행 표시")
    void exportZone_file() throws Exception {
        // 10시 시연용 100명, 11시 실제 80명
        insertHourly(sajik, H0, "100.00", true);
        insertHourly(sajik, H0.plusHours(1), "80.00", false);

        byte[] body = mockMvc.perform(exportRequest("ZONE", sajik, "1h", "2099-01-01T10:00", "2099-01-01T12:00")
                        .param("items", "COUNT", "DENSITY")
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(
                        "filename*=UTF-8''stats_%EC%82%AC%EC%A7%81%EB%8F%99%201%EA%B5%AC%EC%97%AD_20990101_20990101.xlsx")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            Sheet count = workbook.getSheet("인원");
            assertThat(workbook.getSheet("밀집도")).isNotNull();
            Row header = count.getRow(0);
            int demoColumn = header.getLastCellNum() - 1;
            assertThat(header.getCell(demoColumn).getStringCellValue()).isEqualTo("시연용");
            assertThat(count.getLastRowNum()).isEqualTo(2);
            assertThat(count.getRow(1).getCell(4).getStringCellValue()).isEqualTo("사직동 1구역");
            assertThat(count.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(100.0);
            assertThat(count.getRow(1).getCell(demoColumn).getStringCellValue()).isEqualTo("예");
            assertThat(count.getRow(2).getCell(demoColumn).getStringCellValue()).isEqualTo("아니오");
        }
    }

    @Test
    @DisplayName("FNC-C-023 처리 순서 4: 내려받으면 work_history에 EXCEL_DOWNLOAD 1행(행동한 관리자·대상·detail)")
    void exportZone_recordsHistory() throws Exception {
        mockMvc.perform(exportRequest("ZONE", sajik, "1h", "2099-01-01T10:00", "2099-01-01T12:00")
                        .param("items", "COUNT")
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(((Number) row.get("ACTOR_ID")).longValue()).isEqualTo(AdminTestLogin.ADMIN_ID);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        assertThat(row.get("ACTION")).isEqualTo("EXCEL_DOWNLOAD");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ZONE");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(sajik);
        assertThat(row.get("TARGET_NAME")).isEqualTo("사직동 1구역");

        // H2는 JSON 칼럼을 바이트로 돌려준다
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("scope").asString()).isEqualTo("ZONE");
        assertThat(detail.get("targetId").asLong()).isEqualTo(sajik);
        assertThat(detail.get("from").asString()).isEqualTo("2099-01-01T10:00");
        assertThat(detail.get("to").asString()).isEqualTo("2099-01-01T12:00");
        assertThat(detail.get("interval").asString()).isEqualTo("1h");
        assertThat(detail.get("items").get(0).asString()).isEqualTo("COUNT");
        assertThat(detail.get("expand").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("FNC-C-023 전체(ALL)는 대상 종류 ALL, 대상 번호 없음, 대상 이름 '전체'로 기록")
    void exportAll_recordsAllTarget() throws Exception {
        mockMvc.perform(exportRequest("ALL", null, "1d", "2099-01-01", "2099-01-03")
                        .param("items", "COUNT")
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(
                        "stats_%EC%A0%84%EC%B2%B4_20990101_20990102.xlsx")));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT target_type, target_id, target_name FROM work_history");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ALL");
        assertThat(row.get("TARGET_ID")).isNull();
        assertThat(row.get("TARGET_NAME")).isEqualTo("전체");
    }

    @Test
    @DisplayName("FNC-C-023 기간 초과는 400 RANGE_TOO_LONG이고 작업 기록이 생기지 않는다")
    void rangeTooLong() throws Exception {
        mockMvc.perform(exportRequest("ZONE", sajik, "1m", "2099-01-01", "2099-01-09")
                        .param("items", "COUNT")
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));

        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("FNC-C-023 검수 기준 100만 행 제한: 1분 7일 × 구역 100개는 400 TOO_MANY_ROWS이고 작업 기록이 생기지 않는다")
    void tooManyRows() throws Exception {
        for (int i = 1; i < 100; i++) {
            insertZone("11110530", "사직동 추가 " + i);
        }

        mockMvc.perform(exportRequest("ALL", null, "1m", "2099-01-01", "2099-01-08")
                        .param("items", "COUNT")
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));

        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("FNC-C-023 항목이 없으면 400 INVALID_INPUT(items 칸 오류)")
    void itemsRequired() throws Exception {
        mockMvc.perform(exportRequest("ZONE", sajik, "1h", "2099-01-01T10:00", "2099-01-01T12:00")
                        .with(AdminTestLogin.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items"));

        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("FNC-C-023 로그인하지 않은 요청은 401이고 작업 기록이 생기지 않는다")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(exportRequest("ZONE", sajik, "1h", "2099-01-01T10:00", "2099-01-01T12:00")
                        .param("items", "COUNT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(historyCount()).isZero();
    }

    private static MockHttpServletRequestBuilder exportRequest(
            String scope, Long targetId, String interval, String from, String to) {
        MockHttpServletRequestBuilder request = get(EXPORT_URL)
                .param("scope", scope)
                .param("interval", interval)
                .param("from", from)
                .param("to", to);
        return targetId == null ? request : request.param("targetId", String.valueOf(targetId));
    }

    private long historyCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_history", Long.class);
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM camera");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private long insertZone(String dongCode, String name) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES ((SELECT id FROM region WHERE code = ?), ?, 100.00, '{}', 37.600000, 127.002000)",
                dongCode,
                name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private void insertHourly(long zoneId, LocalDateTime statAt, String countAvg, boolean demo) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density,"
                        + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w,"
                        + " dwell_sum_sec, dwell_count, sample_sec, is_demo)"
                        + " VALUES (?, ?, ?, 0, 0, 0.1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3600, ?)",
                zoneId, Timestamp.valueOf(statAt), new BigDecimal(countAvg), demo);
    }
}
