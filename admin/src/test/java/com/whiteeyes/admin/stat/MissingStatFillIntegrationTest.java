package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.stat.service.MissingStatFillService;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatDailyId;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatHourlyId;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 시각은 스케줄러가 건드리지 않을 먼 미래(2099년)로 둔다
/** 빠진 집계 보충(FNC-A-031) */
@SpringBootTest
@Transactional
class MissingStatFillIntegrationTest {

    // 12시 30분 = 12시 시간이 진행 중이고, 오늘은 1월 3일이다
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 3, 12, 30);
    private static final LocalDate YESTERDAY = LocalDate.of(2099, 1, 2);

    @Autowired
    private MissingStatFillService missingStatFillService;

    @Autowired
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    @Autowired
    private ZoneStatHourlyRepository zoneStatHourlyRepository;

    @Autowired
    private ZoneStatDailyRepository zoneStatDailyRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("FNC-A-031 앱을 2시간 끄는 동안 놓친 시간이 채워지고, 이미 있는 행과 진행 중인 현재 시간은 건드리지 않음")
    void fillsHoursMissedWhileAppWasDown() {
        Long zoneId = saveZone("99999993");
        // 9시는 앱이 꺼지기 전에 집계됐다(표시하려고 시연용·특이한 값으로 둔다)
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, hour(9), values("77.00", 3600), true));
        for (int h = 9; h <= 12; h++) {
            minute(zoneId, hour(h), "10.00", 60);
        }

        missingStatFillService.fillMissing(NOW);

        // 10시·11시가 채워졌다
        assertThat(hourly(zoneId, hour(10)).getCountAvg()).isEqualByComparingTo("10.00");
        assertThat(hourly(zoneId, hour(11)).getSampleSec()).isEqualTo(60);
        // 이미 있던 9시는 다시 계산하지 않는다
        assertThat(hourly(zoneId, hour(9)).getCountAvg()).isEqualByComparingTo("77.00");
        assertThat(hourly(zoneId, hour(9)).isDemo()).isTrue();
        // 진행 중인 12시는 제외한다
        assertThat(zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, hour(12)))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-031 시간별은 최근 48시간까지만 찾는다")
    void hourlyLooksBackFortyEightHours() {
        Long zoneId = saveZone("99999993");
        LocalDateTime within = hour(12).minusHours(48);
        LocalDateTime outside = within.minusHours(1);
        minute(zoneId, within, "10.00", 60);
        minute(zoneId, outside, "10.00", 60);

        missingStatFillService.fillMissing(NOW);

        assertThat(zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, within))).isPresent();
        assertThat(zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, outside))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-031 받은 초가 없는 시간(sample_sec=0)은 행을 만들지 않고 보충 대상도 아님")
    void skipsHourWithoutSample() {
        Long zoneId = saveZone("99999993");
        minute(zoneId, hour(8), "0.00", 0);

        missingStatFillService.fillMissing(NOW);
        missingStatFillService.fillMissing(NOW);

        assertThat(zoneStatHourlyRepository.count()).isZero();
    }

    @Test
    @DisplayName("FNC-A-031 시간별을 채운 뒤 일별도 채움(어제 마지막 시간만 놓친 경우)")
    void fillsDailyAfterHourly() {
        Long zoneId = saveZone("99999993");
        minute(zoneId, YESTERDAY.atTime(23, 0), "10.00", 60);

        missingStatFillService.fillMissing(NOW);

        assertThat(zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, YESTERDAY.atTime(23, 0))))
                .isPresent();
        assertThat(zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, YESTERDAY)).orElseThrow()
                        .getSampleSec())
                .isEqualTo(60);
    }

    @Test
    @DisplayName("FNC-A-031 일별은 어제부터 최근 7일까지만, 오늘과 받은 초가 없는 날은 제외")
    void dailyLooksBackSevenDaysAndSkipsTodayAndEmptyDays() {
        Long zoneId = saveZone("99999993");
        LocalDate today = NOW.toLocalDate();
        LocalDate sevenDaysAgo = today.minusDays(7);
        LocalDate eightDaysAgo = today.minusDays(8);
        LocalDate emptyDay = today.minusDays(3);
        for (LocalDate date : new LocalDate[] {today, sevenDaysAgo, eightDaysAgo}) {
            zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, date.atTime(1, 0), values("5.00", 3600), false));
        }
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, emptyDay.atTime(1, 0), values("0.00", 0), false));

        missingStatFillService.fillMissing(NOW);

        assertThat(zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, sevenDaysAgo))).isPresent();
        assertThat(zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, eightDaysAgo))).isEmpty();
        assertThat(zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, today))).isEmpty();
        assertThat(zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, emptyDay))).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-031 다시 실행해도 결과 동일")
    void isIdempotent() {
        Long zoneId = saveZone("99999993");
        minute(zoneId, hour(10), "10.00", 60);
        minute(zoneId, YESTERDAY.atTime(23, 0), "20.00", 30);

        missingStatFillService.fillMissing(NOW);
        long hourlyCount = zoneStatHourlyRepository.count();
        long dailyCount = zoneStatDailyRepository.count();
        missingStatFillService.fillMissing(NOW);

        assertThat(zoneStatHourlyRepository.count()).isEqualTo(hourlyCount).isEqualTo(2);
        assertThat(zoneStatDailyRepository.count()).isEqualTo(dailyCount).isEqualTo(1);
        assertThat(hourly(zoneId, hour(10)).getCountAvg()).isEqualByComparingTo("10.00");
    }

    private LocalDateTime hour(int hourOfToday) {
        return NOW.toLocalDate().atTime(hourOfToday, 0);
    }

    private ZoneStatHourly hourly(Long zoneId, LocalDateTime statAt) {
        return zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, statAt)).orElseThrow();
    }

    private StatValues values(String avg, int sampleSec) {
        return new StatValues(new BigDecimal(avg), 1, 0, new BigDecimal("0.1000"),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, sampleSec);
    }

    // 한 시간에 1분 행 하나만 넣는다(sample_sec가 그 시간의 받은 초)
    private void minute(Long zoneId, LocalDateTime statAt, String avg, int sampleSec) {
        zoneStatMinuteRepository.upsert(ZoneStatMinute.builder()
                .zoneId(zoneId)
                .statAt(statAt)
                .countAvg(new BigDecimal(avg))
                .countMax(12)
                .countMin(8)
                .density(new BigDecimal("0.1000"))
                .sampleSec((short) sampleSec)
                .build());
    }

    // zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 테스트가 끝나면 롤백된다
    private Long saveZone(String regionCode) {
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '" + regionCode + "', '보충 테스트동', '{}', 37.600000, 127.002000)");
        Long dongId = jdbcTemplate.queryForObject(
                "SELECT id FROM region WHERE code = '" + regionCode + "'", Long.class);
        Zone zone = new Zone(dongId, "보충 구역 " + regionCode, new BigDecimal("100.00"), "{}",
                new BigDecimal("37.600000"), new BigDecimal("127.002000"));
        return zoneRepository.save(zone).getId();
    }
}
