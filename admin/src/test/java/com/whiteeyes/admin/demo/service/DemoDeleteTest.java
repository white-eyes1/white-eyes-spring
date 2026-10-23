package com.whiteeyes.admin.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.whiteeyes.admin.demo.repository.DemoStatRepository;
import com.whiteeyes.admin.region.service.RegionLoadService;

// 테스트 행정구역 파일: 서울(11) → 종로구 사직동(11110530)
/** 시연용 데이터 삭제(FNC-A-052) */
@SpringBootTest
class DemoDeleteTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");
    private static final LocalDateTime FIRST_HOUR = LocalDateTime.of(2099, 1, 1, 0, 0);

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private DemoDeleteService demoDeleteService;

    @Autowired
    private DemoStatRepository demoStatRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long zoneId;

    @BeforeEach
    void setUp() {
        deleteRows();
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
        regionLoadService.load(REGION_FILE);
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '11110530'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, '사직동 1구역', 100.00, '{}', 37.600000, 127.002000)",
                dongId);
        zoneId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    // 같은 H2를 쓰는 다른 테스트가 zone·region을 지울 수 있게 넣은 통계·구역을 지우고 끝낸다(FK)
    @AfterEach
    void tearDown() {
        deleteRows();
    }

    @Test
    @DisplayName("FNC-A-052 시간별·일별에서 시연용(is_demo=1) 행만 지우고 실제 데이터(is_demo=0)는 남긴다")
    void deletesOnlyDemoRows() {
        // 시간별: 시연용 5시간, 실제 3시간 / 일별: 시연용 2일, 실제 1일
        insertHourly(0, 5, true);
        insertHourly(5, 3, false);
        insertDaily(0, 2, true);
        insertDaily(2, 1, false);

        DemoDeleteResult result = demoDeleteService.deleteAll();

        assertThat(result.hourly()).isEqualTo(5);
        assertThat(result.daily()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(7);
        assertThat(count("zone_stat_hourly", true)).isZero();
        assertThat(count("zone_stat_hourly", false)).isEqualTo(3);
        assertThat(count("zone_stat_daily", true)).isZero();
        assertThat(count("zone_stat_daily", false)).isEqualTo(1);
    }

    @Test
    @DisplayName("FNC-A-052 정해진 건수씩 나눠 지우고, 마지막 묶음이 묶음 크기와 같아도 남김없이 지운다")
    void deletesInBatches() {
        // 묶음 크기 3: 시간별 7건 = 3 + 3 + 1, 일별 6건 = 3 + 3 + 0(한 번 더 확인하고 끝남)
        insertHourly(0, 7, true);
        insertDaily(0, 6, true);
        insertHourly(7, 2, false);

        DemoDeleteResult result = new DemoDeleteService(demoStatRepository, 3).deleteAll();

        assertThat(result.hourly()).isEqualTo(7);
        assertThat(result.daily()).isEqualTo(6);
        assertThat(count("zone_stat_hourly", true)).isZero();
        assertThat(count("zone_stat_daily", true)).isZero();
        assertThat(count("zone_stat_hourly", false)).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-052 시연용 데이터가 없으면 0건으로 끝난다")
    void nothingToDelete() {
        insertHourly(0, 2, false);

        DemoDeleteResult result = demoDeleteService.deleteAll();

        assertThat(result.total()).isZero();
        assertThat(count("zone_stat_hourly", false)).isEqualTo(2);
    }

    private void deleteRows() {
        jdbcTemplate.update("DELETE FROM zone_stat_hourly");
        jdbcTemplate.update("DELETE FROM zone_stat_daily");
        jdbcTemplate.update("DELETE FROM zone");
    }

    private long count(String table, boolean demo) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE is_demo = ?", Long.class, demo ? 1 : 0);
    }

    // start번째 시간부터 count시간치
    private void insertHourly(int start, int count, boolean demo) {
        for (int i = start; i < start + count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO zone_stat_hourly (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec, is_demo)"
                            + " VALUES (?, ?, ?, 0, 0, 0, 3600, ?)",
                    zoneId,
                    Timestamp.valueOf(FIRST_HOUR.plusHours(i)),
                    new BigDecimal("10.00"),
                    demo ? 1 : 0);
        }
    }

    // start번째 날부터 count일치
    private void insertDaily(int start, int count, boolean demo) {
        for (int i = start; i < start + count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO zone_stat_daily (zone_id, stat_date, count_avg, count_max, count_min, density, sample_sec, is_demo)"
                            + " VALUES (?, ?, ?, 0, 0, 0, 86400, ?)",
                    zoneId,
                    Date.valueOf(LocalDate.of(2099, 1, 1).plusDays(i)),
                    new BigDecimal("10.00"),
                    demo ? 1 : 0);
        }
    }
}
