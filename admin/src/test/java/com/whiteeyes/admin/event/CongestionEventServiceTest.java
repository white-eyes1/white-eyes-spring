package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.event.service.CongestionEventService;
import com.whiteeyes.admin.stat.service.DirectionCounts;
import com.whiteeyes.admin.stat.service.MinuteAccumulatorStore;
import com.whiteeyes.admin.stat.service.MinuteStatService;
import com.whiteeyes.admin.stat.service.SecondResult;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.entity.EventStatus;
import com.whiteeyes.common.event.entity.EventType;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 혼잡 이벤트 판정(FNC-A-033). 기준은 기본값(주의 0.7, 위험 1.5 명/㎡, 최소 30초, 종료 유지 5분)이다.
 * 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에 테스트 시각은 먼 미래(2099년)로 둔다. 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class CongestionEventServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final short FULL_SAMPLE_SEC = 60;
    private static final int MIN_SAMPLE_SEC = 30;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private CongestionEventService congestionEventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private MinuteAccumulatorStore minuteAccumulatorStore;

    @Autowired
    private MinuteStatService minuteStatService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("FNC-A-033 단계 경계 근처 오르내림(0.69→0.70→0.69→0.71…)에서 이벤트가 하나로 유지되고 복귀하면 below_since가 비워짐")
    void keepsSingleEventWhileDensityHoversAroundBoundary() {
        Long zoneId = saveZone("테스트 경계 구역", "1");
        String[] densities = {"0.69", "0.70", "0.69", "0.71", "0.69", "0.70", "0.69", "0.72"};

        for (int minute = 0; minute < densities.length; minute++) {
            judge(zoneId, minute, densities[minute], FULL_SAMPLE_SEC);
        }

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEndedAt()).isNull();
        // 마지막 1분(0.72)이 주의 이상으로 돌아왔으므로 below_since는 비어 있다
        assertThat(events.get(0).getBelowSince()).isNull();
        assertThat(events.get(0).getStartedAt()).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033 주의 미만이 되면 below_since에 그 1분 시각을 기록")
    void recordsBelowSinceWhenLevelDropsBelowCaution() {
        Long zoneId = saveZone("테스트 하락 구역", "1");

        judge(zoneId, 0, "0.80", FULL_SAMPLE_SEC);
        judge(zoneId, 1, "0.69", FULL_SAMPLE_SEC);
        judge(zoneId, 2, "0.60", FULL_SAMPLE_SEC);

        Event event = eventRepository.findAll().get(0);
        // 처음 내려간 시각을 유지한다
        assertThat(event.getBelowSince()).isEqualTo(T0.plusMinutes(1));
        assertThat(event.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("FNC-A-033 sample_sec가 29이면 건너뛰고 30이면 판정(below_since도 건드리지 않음)")
    void skipsMinuteWithTooFewSamples() {
        Long zoneId = saveZone("테스트 표본 구역", "1");

        judge(zoneId, 0, "1.00", (short) (MIN_SAMPLE_SEC - 1));
        assertThat(eventRepository.findAll()).isEmpty();

        judge(zoneId, 1, "1.00", (short) MIN_SAMPLE_SEC);
        assertThat(eventRepository.findAll()).hasSize(1);

        judge(zoneId, 2, "0.10", FULL_SAMPLE_SEC);
        Event event = eventRepository.findAll().get(0);
        assertThat(event.getBelowSince()).isEqualTo(T0.plusMinutes(2));

        // 불완전한 1분은 below_since를 지우지도 닫지도 않는다
        judge(zoneId, 3, "1.00", (short) (MIN_SAMPLE_SEC - 1));
        judge(zoneId, 9, "0.10", (short) (MIN_SAMPLE_SEC - 1));
        assertThat(event.getBelowSince()).isEqualTo(T0.plusMinutes(2));
        assertThat(event.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("FNC-A-033 주의 미만이면 이벤트를 만들지 않음")
    void createsNoEventBelowCaution() {
        Long zoneId = saveZone("테스트 정상 구역", "1");

        judge(zoneId, 0, "0.6999", FULL_SAMPLE_SEC);

        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-033 새 이벤트는 판정한 1분의 시각·최고 값·당시 기준값과 미확인 상태로 저장")
    void createsEventWithPeakAndSnapshot() throws Exception {
        Long zoneId = saveZone("테스트 신규 구역", "100");

        judgeStat(stat(zoneId, T0, "52.50", "0.7500", FULL_SAMPLE_SEC));
        entityManager.flush();

        Event event = eventRepository.findAll().get(0);
        assertThat(event.getType()).isEqualTo(EventType.CONGESTION);
        assertThat(event.getZoneId()).isEqualTo(zoneId);
        assertThat(event.getCameraId()).isNull();
        assertThat(event.getStartedAt()).isEqualTo(T0);
        assertThat(event.getEndedAt()).isNull();
        assertThat(event.getPeakLevel()).isEqualTo(2);
        assertThat(event.getPeakValue()).isEqualByComparingTo("0.7500");
        // 52.50명은 반올림해 53명
        assertThat(event.getPeakCount()).isEqualTo(53);
        assertThat(event.getBelowSince()).isNull();
        assertThat(event.getStatus()).isEqualTo(EventStatus.UNCHECKED);
        JsonNode snapshot = jsonMapper.readTree(event.getThresholdSnapshot());
        assertThat(snapshot.get("warnDensity").decimalValue()).isEqualByComparingTo("0.7");
        assertThat(snapshot.get("dangerDensity").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM event WHERE id = ?", String.class, event.getId()))
                .isEqualTo("UNCHECKED");
    }

    @Test
    @DisplayName("FNC-A-033 위험으로 오르면 같은 이벤트의 최고 단계·값·인원을 갱신하고, 내려가도 최고 값은 유지")
    void raisesPeakOfSameEvent() {
        Long zoneId = saveZone("테스트 상승 구역", "100");

        judgeStat(stat(zoneId, T0, "80.00", "0.8000", FULL_SAMPLE_SEC));
        judgeStat(stat(zoneId, T0.plusMinutes(1), "160.40", "1.6040", FULL_SAMPLE_SEC));
        judgeStat(stat(zoneId, T0.plusMinutes(2), "90.00", "0.9000", FULL_SAMPLE_SEC));

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.getStartedAt()).isEqualTo(T0);
        assertThat(event.getPeakLevel()).isEqualTo(3);
        assertThat(event.getPeakValue()).isEqualByComparingTo("1.6040");
        assertThat(event.getPeakCount()).isEqualTo(160);
    }

    @Test
    @DisplayName("FNC-A-033 주의 미만이 4분 유지되면 이벤트가 열려 있고 5분 유지되면 ended_at이 그 1분 시각")
    void closesEventAfterHoldMinutes() {
        Long zoneId = saveZone("테스트 종료 구역", "1");

        judge(zoneId, 0, "0.80", FULL_SAMPLE_SEC);
        judge(zoneId, 1, "0.50", FULL_SAMPLE_SEC);
        judge(zoneId, 5, "0.50", FULL_SAMPLE_SEC);
        Event event = eventRepository.findAll().get(0);
        assertThat(event.getBelowSince()).isEqualTo(T0.plusMinutes(1));
        assertThat(event.getEndedAt()).isNull();

        judge(zoneId, 6, "0.50", FULL_SAMPLE_SEC);

        assertThat(event.getEndedAt()).isEqualTo(T0.plusMinutes(6));
    }

    @Test
    @DisplayName("FNC-A-033 주의 미만이 4분 이어지다 다시 주의 이상이 되면 이벤트가 하나로 유지되고 below_since가 비워짐")
    void keepsEventWhenLevelRecoversBeforeHold() {
        Long zoneId = saveZone("테스트 복귀 구역", "1");

        judge(zoneId, 0, "0.80", FULL_SAMPLE_SEC);
        judge(zoneId, 1, "0.50", FULL_SAMPLE_SEC);
        judge(zoneId, 5, "0.50", FULL_SAMPLE_SEC);
        judge(zoneId, 6, "0.80", FULL_SAMPLE_SEC);
        // 복귀한 뒤 다시 내려가면 유지 시간은 처음부터 센다
        judge(zoneId, 7, "0.50", FULL_SAMPLE_SEC);
        judge(zoneId, 11, "0.50", FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEndedAt()).isNull();
        assertThat(events.get(0).getBelowSince()).isEqualTo(T0.plusMinutes(7));
    }

    @Test
    @DisplayName("FNC-A-033 종료된 뒤 다시 주의 이상이 되면 새 이벤트를 만듦")
    void opensNewEventAfterPreviousOneEnded() {
        Long zoneId = saveZone("테스트 재상승 구역", "1");
        judge(zoneId, 0, "0.80", FULL_SAMPLE_SEC);
        judge(zoneId, 1, "0.50", FULL_SAMPLE_SEC);
        judge(zoneId, 6, "0.50", FULL_SAMPLE_SEC);

        judge(zoneId, 7, "0.90", FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(Event::getEndedAt).containsExactlyInAnyOrder(T0.plusMinutes(6), null);
        assertThat(events)
                .filteredOn(event -> event.getEndedAt() == null)
                .singleElement()
                .satisfies(open -> assertThat(open.getStartedAt()).isEqualTo(T0.plusMinutes(7)));
    }

    @Test
    @DisplayName("FNC-A-033 한 구역의 이벤트는 다른 구역의 판정에 영향을 주지 않음")
    void zonesAreIndependent() {
        Long zoneA = saveZone("테스트 A 구역", "1");
        Long zoneB = saveZone("테스트 B 구역", "1");

        judge(zoneA, 0, "0.80", FULL_SAMPLE_SEC);
        judge(zoneB, 0, "0.30", FULL_SAMPLE_SEC);
        judge(zoneB, 1, "0.30", FULL_SAMPLE_SEC);
        judge(zoneB, 6, "0.30", FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getZoneId()).isEqualTo(zoneA);
        assertThat(events.get(0).getBelowSince()).isNull();
        assertThat(events.get(0).getEndedAt()).isNull();
    }

    @Test
    @DisplayName("FNC-A-033 1분 마감(MinuteStatService.closeEndedMinutes)으로 통계를 저장하면 이벤트가 만들어짐")
    void closingMinuteCreatesEvent() {
        // 면적 50㎡에 40명이면 밀집도 0.8
        Long zoneId = saveZone("테스트 마감 구역", "50");
        for (int second = 0; second < MIN_SAMPLE_SEC; second++) {
            minuteAccumulatorStore.add(
                    zoneId,
                    T0.plusSeconds(second),
                    new SecondResult(40, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
        }

        minuteStatService.closeEndedMinutes(T0.plusMinutes(1).plusSeconds(5));

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getZoneId()).isEqualTo(zoneId);
        assertThat(events.get(0).getStartedAt()).isEqualTo(T0);
        assertThat(events.get(0).getPeakValue()).isEqualByComparingTo("0.8000");
        assertThat(events.get(0).getPeakCount()).isEqualTo(40);
    }

    private void judge(Long zoneId, int minuteOffset, String density, short sampleSec) {
        // 면적 1㎡ 구역에서는 인원 = 밀집도
        judgeStat(stat(zoneId, T0.plusMinutes(minuteOffset), density, density, sampleSec));
    }

    private void judgeStat(ZoneStatMinute stat) {
        congestionEventService.judge(
                stat.getZoneId(), stat.getStatAt(), stat.getCountAvg(), stat.getDensity(), stat.getSampleSec());
    }

    private ZoneStatMinute stat(Long zoneId, LocalDateTime statAt, String countAvg, String density, short sampleSec) {
        return ZoneStatMinute.builder()
                .zoneId(zoneId)
                .statAt(statAt)
                .countAvg(new BigDecimal(countAvg))
                .countMax(1)
                .countMin(0)
                .density(new BigDecimal(density))
                .sampleSec(sampleSec)
                .build();
    }

    // zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 구역마다 다른 동 코드를 쓴다
    private Long saveZone(String name, String areaM2) {
        String code = String.valueOf(99990000 + Math.floorMod(name.hashCode(), 9999));
        jdbcTemplate.update(
                "INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                        + " VALUES ('EMD', ?, ?, '{}', 37.600000, 127.002000)",
                code,
                name + " 동");
        Long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
        Zone zone = new Zone(
                dongId, name, new BigDecimal(areaM2), "{}", new BigDecimal("37.600000"), new BigDecimal("127.002000"));
        return zoneRepository.save(zone).getId();
    }
}
