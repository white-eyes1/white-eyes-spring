package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.stat.service.DailyStatService;
import com.whiteeyes.admin.stat.service.HourlyStatService;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatDailyId;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatHourlyId;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;

// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 시각은 스케줄러가 건드리지 않을 먼 미래(2099년)로 둔다
/** 시간별(FNC-A-029)·일별(FNC-A-030) 통계 저장 */
@SpringBootTest
@Transactional
class HourlyDailyStatIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);
    private static final LocalDateTime HOUR = DAY.atTime(10, 0);

    @Autowired
    private HourlyStatService hourlyStatService;

    @Autowired
    private DailyStatService dailyStatService;

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
    @DisplayName("FNC-A-029 1분 통계를 sample_sec 가중 평균으로 묶어 저장하고, 다시 실행해도 결과 동일")
    void hourlyIsIdempotent() {
        Long zoneId = saveZone("99999991");
        zoneStatMinuteRepository.upsert(minute(zoneId, HOUR, "10.00", 12, 8, 60, 3));
        zoneStatMinuteRepository.upsert(minute(zoneId, HOUR.plusMinutes(1), "40.00", 50, 30, 30, 5));

        hourlyStatService.aggregateHour(HOUR);
        hourlyStatService.aggregateHour(HOUR);

        assertThat(zoneStatHourlyRepository.count()).isEqualTo(1);
        ZoneStatHourly hourly = zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, HOUR)).orElseThrow();
        // (10×60 + 40×30) ÷ 90
        assertThat(hourly.getCountAvg()).isEqualByComparingTo("20.00");
        assertThat(hourly.getCountMax()).isEqualTo(50);
        assertThat(hourly.getCountMin()).isEqualTo(8);
        assertThat(hourly.getInN()).isEqualTo(8);
        assertThat(hourly.getSampleSec()).isEqualTo(90);
        assertThat(hourly.isDemo()).isFalse();
    }

    @Test
    @DisplayName("FNC-A-029 밀집도는 가중 평균, 방향별 in/out과 체류 시간은 합")
    void hourlySumsDirectionsAndDwell() {
        Long zoneId = saveZone("99999991");
        zoneStatMinuteRepository.upsert(ZoneStatMinute.builder()
                .zoneId(zoneId).statAt(HOUR).countAvg(new BigDecimal("10.00")).countMax(12).countMin(8)
                .density(new BigDecimal("0.1000")).inE(1).inW(2).outN(3).outS(4)
                .dwellSumSec(100).dwellCount(2).sampleSec((short) 60).build());
        zoneStatMinuteRepository.upsert(ZoneStatMinute.builder()
                .zoneId(zoneId).statAt(HOUR.plusMinutes(1)).countAvg(new BigDecimal("40.00")).countMax(50)
                .countMin(30).density(new BigDecimal("0.4000")).inE(10).inW(20).outN(30).outS(40)
                .dwellSumSec(50).dwellCount(1).sampleSec((short) 30).build());

        hourlyStatService.aggregateHour(HOUR);

        ZoneStatHourly hourly = zoneStatHourlyRepository.findById(new ZoneStatHourlyId(zoneId, HOUR)).orElseThrow();
        // (0.1×60 + 0.4×30) ÷ 90
        assertThat(hourly.getDensity()).isEqualByComparingTo("0.2000");
        assertThat(hourly.getInE()).isEqualTo(11);
        assertThat(hourly.getInW()).isEqualTo(22);
        assertThat(hourly.getOutN()).isEqualTo(33);
        assertThat(hourly.getOutS()).isEqualTo(44);
        assertThat(hourly.getDwellSumSec()).isEqualTo(150);
        assertThat(hourly.getDwellCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("FNC-A-029 받은 초가 없는 구역(sample_sec=0)은 시간 행을 만들지 않음")
    void hourlySkipsZoneWithoutSample() {
        Long zoneId = saveZone("99999991");
        zoneStatMinuteRepository.upsert(minute(zoneId, HOUR, "0.00", 0, 0, 0, 0));

        hourlyStatService.aggregateHour(HOUR);

        assertThat(zoneStatHourlyRepository.count()).isZero();
    }

    @Test
    @DisplayName("FNC-A-029 다른 시간의 1분 통계는 섞이지 않고, 1분 행이 없는 구역은 시간 행을 만들지 않음")
    void hourlyUsesOnlyThatHourAndSkipsZonesWithoutMinutes() {
        Long zoneId = saveZone("99999991");
        saveZone("99999992");
        zoneStatMinuteRepository.upsert(minute(zoneId, HOUR.minusMinutes(1), "99.00", 99, 99, 60, 0));
        zoneStatMinuteRepository.upsert(minute(zoneId, HOUR, "10.00", 10, 10, 60, 0));
        zoneStatMinuteRepository.upsert(minute(zoneId, HOUR.plusHours(1), "99.00", 99, 99, 60, 0));

        hourlyStatService.aggregateHour(HOUR);

        List<ZoneStatHourly> saved = zoneStatHourlyRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getZoneId()).isEqualTo(zoneId);
        assertThat(saved.get(0).getCountAvg()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("FNC-A-030 그날 시간별 통계를 묶어 저장하고, 다시 실행해도 결과 동일")
    void dailyIsIdempotent() {
        Long zoneId = saveZone("99999991");
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, DAY.atTime(9, 0),
                hourValues("10.00", 20, 5, 3600, 10), false));
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, DAY.atTime(10, 0),
                hourValues("30.00", 60, 2, 1800, 20), false));
        // 다른 날짜의 시간별은 섞이지 않는다
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, DAY.plusDays(1).atStartOfDay(),
                hourValues("99.00", 99, 99, 3600, 99), false));

        dailyStatService.aggregateDay(DAY);
        dailyStatService.aggregateDay(DAY);

        assertThat(zoneStatDailyRepository.count()).isEqualTo(1);
        ZoneStatDaily daily = zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, DAY)).orElseThrow();
        // (10×3600 + 30×1800) ÷ 5400
        assertThat(daily.getCountAvg()).isEqualByComparingTo("16.67");
        assertThat(daily.getCountMax()).isEqualTo(60);
        assertThat(daily.getCountMin()).isEqualTo(2);
        assertThat(daily.getInN()).isEqualTo(30);
        assertThat(daily.getSampleSec()).isEqualTo(5400);
        assertThat(daily.isDemo()).isFalse();
    }

    @Test
    @DisplayName("FNC-A-030 그날 시간별 행 중 하나라도 시연용이면 일별 행도 시연용")
    void dailyIsDemoIfAnyHourIsDemo() {
        Long zoneId = saveZone("99999991");
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, DAY.atTime(9, 0),
                hourValues("10.00", 20, 5, 3600, 10), false));
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, DAY.atTime(10, 0),
                hourValues("10.00", 20, 5, 3600, 10), true));

        dailyStatService.aggregateDay(DAY);

        assertThat(zoneStatDailyRepository.findById(new ZoneStatDailyId(zoneId, DAY)).orElseThrow().isDemo())
                .isTrue();
    }

    private ZoneStatMinute minute(
            Long zoneId, LocalDateTime statAt, String avg, int max, int min, int sampleSec, int inN) {
        return ZoneStatMinute.builder()
                .zoneId(zoneId)
                .statAt(statAt)
                .countAvg(new BigDecimal(avg))
                .countMax(max)
                .countMin(min)
                .density(new BigDecimal(avg).divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP))
                .inN(inN)
                .sampleSec((short) sampleSec)
                .build();
    }

    private StatValues hourValues(String avg, int max, int min, int sampleSec, int inN) {
        return new StatValues(new BigDecimal(avg), max, min, new BigDecimal("0.1000"),
                inN, 0, 0, 0, 0, 0, 0, 0, 0, 0, sampleSec);
    }


    // zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 테스트가 끝나면 롤백된다
    private Long saveZone(String regionCode) {
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '" + regionCode + "', '통계 테스트동', '{}', 37.600000, 127.002000)");
        Long dongId = jdbcTemplate.queryForObject(
                "SELECT id FROM region WHERE code = '" + regionCode + "'", Long.class);
        Zone zone = new Zone(dongId, "통계 구역 " + regionCode, new BigDecimal("100.00"), "{}",
                new BigDecimal("37.600000"), new BigDecimal("127.002000"));
        return zoneRepository.save(zone).getId();
    }
}
