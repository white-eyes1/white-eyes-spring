package com.whiteeyes.admin.event;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.whiteeyes.admin.notification.service.NotificationPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import com.whiteeyes.admin.event.service.SurgeEventService;
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

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 급증 이벤트 판정(FNC-A-034). 기준은 기본값(비교 10분, 증가율 50%, 최소 밀집도 0.2, 최소 30초, 종료 유지 5분)이다.
 * 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에 테스트 시각은 먼 미래(2099년)로 둔다. 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class SurgeEventServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final int FULL_SAMPLE_SEC = 60;
    private static final int MIN_SAMPLE_SEC = 30;
    private static final String ENOUGH_DENSITY = "1.0000";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @MockitoSpyBean
    private NotificationPublisher notificationPublisher;

    @Autowired
    private SurgeEventService surgeEventService;

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
    @DisplayName("FNC-A-034 5명→10명 같은 소규모 증가는 밀집도가 최소 기준 미만이라 무시")
    void ignoresSmallIncreaseBelowMinDensity() {
        Long zoneId = saveZone("테스트 소규모 구역", "100");

        // 면적 100㎡에서 10명은 밀집도 0.1
        surgeEventService.judge(zoneId, T0, bd("10"), bd("0.1000"), FULL_SAMPLE_SEC, bd("5"), FULL_SAMPLE_SEC);

        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-034 새 급증 이벤트를 열면 관리자 화면 알림을 보내고, 같은 이벤트 갱신에는 보내지 않는다")
    void notifiesOnlyWhenNewSurgeEventIsOpened() {
        Long zoneId = saveZone("테스트 알림 급증 구역", "10");

        surgeEventService.judge(zoneId, T0, bd("10"), bd("1.0000"), FULL_SAMPLE_SEC, bd("5"), FULL_SAMPLE_SEC);
        surgeEventService.judge(
                zoneId, T0.plusMinutes(1), bd("20"), bd("2.0000"), FULL_SAMPLE_SEC, bd("5"), FULL_SAMPLE_SEC);

        Event event = eventRepository.findAll().get(0);
        verify(notificationPublisher, times(1))
                .eventCreated(eq(event.getId()), eq(EventType.SURGE), eq(zoneId), isNull(), eq(T0));
        verify(notificationPublisher, times(1)).uncheckedCountChanged();
    }

    @Test
    @DisplayName("FNC-A-034 이벤트를 열지 않으면 알림을 보내지 않는다")
    void doesNotNotifyWithoutSurgeEvent() {
        Long zoneId = saveZone("테스트 무알림 급증 구역", "100");

        surgeEventService.judge(zoneId, T0, bd("10"), bd("0.1000"), FULL_SAMPLE_SEC, bd("5"), FULL_SAMPLE_SEC);

        verify(notificationPublisher, never()).uncheckedCountChanged();
    }

    @Test
    @DisplayName("FNC-A-034 같은 5명→10명이라도 구역이 작아 밀집도가 최소 기준 이상이면 이벤트를 엶")
    void opensWhenSmallZoneReachesMinDensity() {
        Long zoneId = saveZone("테스트 작은 구역", "10");

        surgeEventService.judge(zoneId, T0, bd("10"), bd("1.0000"), FULL_SAMPLE_SEC, bd("5"), FULL_SAMPLE_SEC);

        assertThat(eventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-034 밀집도 0.2는 검사하고 0.1999는 무시")
    void minDensityBoundary() {
        Long below = saveZone("테스트 밀집도 미만 구역", "100");
        Long equal = saveZone("테스트 밀집도 경계 구역", "100");

        surgeEventService.judge(below, T0, bd("20"), bd("0.1999"), FULL_SAMPLE_SEC, bd("10"), FULL_SAMPLE_SEC);
        surgeEventService.judge(equal, T0, bd("20"), bd("0.2000"), FULL_SAMPLE_SEC, bd("10"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getZoneId()).isEqualTo(equal);
    }

    @Test
    @DisplayName("FNC-A-034 증가율 50.00%는 이벤트를 열고 49.99%는 열지 않음")
    void rateBoundary() {
        Long below = saveZone("테스트 증가율 미만 구역", "10");
        Long equal = saveZone("테스트 증가율 경계 구역", "10");

        surgeEventService.judge(below, T0, bd("149.99"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(equal, T0, bd("150.00"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getZoneId()).isEqualTo(equal);
    }

    @Test
    @DisplayName("FNC-A-034 새 이벤트는 증가율·인원·당시 기준값과 미확인 상태로 저장하고 단계는 비움")
    void createsEventWithRateAndSnapshot() throws Exception {
        Long zoneId = saveZone("테스트 신규 구역", "10");

        // 30.00 → 52.50: 증가율 75%, 인원은 반올림해 53명
        surgeEventService.judge(zoneId, T0, bd("52.50"), bd("5.2500"), FULL_SAMPLE_SEC, bd("30.00"), FULL_SAMPLE_SEC);
        entityManager.flush();

        Event event = eventRepository.findAll().get(0);
        assertThat(event.getType()).isEqualTo(EventType.SURGE);
        assertThat(event.getZoneId()).isEqualTo(zoneId);
        assertThat(event.getCameraId()).isNull();
        assertThat(event.getStartedAt()).isEqualTo(T0);
        assertThat(event.getEndedAt()).isNull();
        assertThat(event.getPeakLevel()).isNull();
        assertThat(event.getPeakValue()).isEqualByComparingTo("75.0000");
        assertThat(event.getPeakCount()).isEqualTo(53);
        assertThat(event.getBelowSince()).isNull();
        assertThat(event.getStatus()).isEqualTo(EventStatus.UNCHECKED);
        JsonNode snapshot = jsonMapper.readTree(event.getThresholdSnapshot());
        assertThat(snapshot.get("window").asInt()).isEqualTo(10);
        assertThat(snapshot.get("rate").decimalValue()).isEqualByComparingTo("50");
        assertThat(snapshot.get("minDensity").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(jdbcTemplate.queryForObject("SELECT type FROM event WHERE id = ?", String.class, event.getId()))
                .isEqualTo("SURGE");
    }

    @Test
    @DisplayName("FNC-A-034 증가율이 소수로 떨어지면 소수 넷째 자리까지 반올림해 저장")
    void roundsRateToFourDecimals() {
        Long zoneId = saveZone("테스트 반올림 구역", "10");

        // (20 - 12) / 12 * 100 = 66.66666…
        surgeEventService.judge(zoneId, T0, bd("20"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("12"), FULL_SAMPLE_SEC);

        assertThat(eventRepository.findAll().get(0).getPeakValue()).isEqualByComparingTo("66.6667");
    }

    @Test
    @DisplayName("FNC-A-034 증가율 상한 경계: 999999.9999는 그대로, 1000000.0000은 상한값으로 저장")
    void rateCapBoundary() {
        Long equal = saveZone("테스트 상한 경계 구역", "10");
        Long over = saveZone("테스트 상한 초과 구역", "10");

        // 10분 전 1명: 10000.999999명이면 증가율 정확히 999999.9999, 10001명이면 1000000.0000
        surgeEventService.judge(equal, T0, bd("10000.999999"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("1"), FULL_SAMPLE_SEC);
        surgeEventService.judge(over, T0, bd("10001"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("1"), FULL_SAMPLE_SEC);
        entityManager.flush();

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(Event::getPeakValue)
                .allSatisfy(value -> assertThat(value).isEqualByComparingTo("999999.9999"));
    }

    @Test
    @DisplayName("FNC-A-034 열린 급증 이벤트가 있으면 새로 만들지 않고 증가율·인원의 최댓값만 갱신하며 below_since를 비움")
    void updatesPeaksOfOpenEvent() {
        Long zoneId = saveZone("테스트 갱신 구역", "10");

        surgeEventService.judge(zoneId, T0, bd("160"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        // 증가율 20%로 내려가 below_since가 기록된다
        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("120"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        assertThat(eventRepository.findAll().get(0).getBelowSince()).isEqualTo(T0.plusMinutes(1));
        // 증가율 100%, 인원 200으로 더 오르면 최댓값을 갱신하고 below_since를 비운다
        surgeEventService.judge(zoneId, T0.plusMinutes(2), bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        // 증가율 55%, 인원 155로 내려오면 최댓값은 유지한다
        surgeEventService.judge(zoneId, T0.plusMinutes(3), bd("155"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.getStartedAt()).isEqualTo(T0);
        assertThat(event.getPeakValue()).isEqualByComparingTo("100.0000");
        assertThat(event.getPeakCount()).isEqualTo(200);
        assertThat(event.getBelowSince()).isNull();
    }

    @Test
    @DisplayName("FNC-A-034 비교할 10분 전 행이 없거나 부족하면 조건 미충족이라 이벤트를 열지 않음")
    void doesNotOpenWithoutUsableBaseline() {
        Long zoneId = saveZone("테스트 기준 없음 구역", "10");

        // 10분 전 행 없음
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, null, null);
        // 10분 전 결과 초 수 29
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), MIN_SAMPLE_SEC - 1);
        // 10분 전 인원 0 (0으로 나눌 수 없다)
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("0.00"), FULL_SAMPLE_SEC);

        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-034 현재 결과 초 수가 30 미만이면 판정을 건너뛰어 이벤트를 열지 않고 열린 이벤트의 below_since도 건드리지 않음")
    void skipsJudgementWhenCurrentSampleTooShort() {
        Long zoneId = saveZone("테스트 현재 초 수 부족 구역", "10");
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        Event event = eventRepository.findAll().get(0);

        // 기준 미만이어도, 비교 행이 없어도 초 수가 29면 건너뛴다
        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("110"), bd(ENOUGH_DENSITY), MIN_SAMPLE_SEC - 1, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(2), bd("200"), bd(ENOUGH_DENSITY), MIN_SAMPLE_SEC - 1, null, null);
        assertThat(event.getBelowSince()).isNull();

        // 이미 below_since가 있으면 그 값도 그대로 두고 종료하지 않는다
        surgeEventService.judge(zoneId, T0.plusMinutes(3), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(9), bd("110"), bd(ENOUGH_DENSITY), MIN_SAMPLE_SEC - 1, bd("100"), FULL_SAMPLE_SEC);
        assertThat(event.getBelowSince()).isEqualTo(T0.plusMinutes(3));
        assertThat(event.getEndedAt()).isNull();

        // 열린 이벤트가 없을 때도 새로 열지 않는다
        Long other = saveZone("테스트 현재 초 수 부족 다른 구역", "10");
        surgeEventService.judge(other, T0, bd("200"), bd(ENOUGH_DENSITY), MIN_SAMPLE_SEC - 1, bd("100"), FULL_SAMPLE_SEC);
        assertThat(eventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-034 증가율이 peak_value 상한 999999.9999를 넘으면 상한값으로 잘라 저장")
    void capsRateAtPeakValueLimit() {
        Long zoneId = saveZone("테스트 증가율 상한 구역", "10");

        // 10분 전 0.0001명 → 100명: 증가율 약 100,000,000%로 상한을 넘는다
        surgeEventService.judge(zoneId, T0, bd("100"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("0.0001"), FULL_SAMPLE_SEC);
        entityManager.flush();

        Event event = eventRepository.findAll().get(0);
        assertThat(event.getPeakValue()).isEqualByComparingTo("999999.9999");
        assertThat(event.getPeakCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("FNC-A-034 10분 전 결과 초 수 30은 비교에 쓰고, 현재 결과 초 수 30도 검사")
    void sampleSecBoundaryIsInclusive() {
        Long zoneId = saveZone("테스트 초 수 경계 구역", "10");

        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), MIN_SAMPLE_SEC, bd("100"), MIN_SAMPLE_SEC);

        assertThat(eventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("FNC-A-034 열린 이벤트가 있을 때 조건 미충족이 이어지면 처음 시각을 below_since에 남기고 5분 유지 시 닫음")
    void closesAfterHoldWhenConditionNotMet() {
        Long zoneId = saveZone("테스트 조건 미충족 종료 구역", "10");
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        Event event = eventRepository.findAll().get(0);

        // 비교 행이 없는 상태가 이어진다
        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, null, null);
        surgeEventService.judge(zoneId, T0.plusMinutes(5), bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, null, null);
        assertThat(event.getBelowSince()).isEqualTo(T0.plusMinutes(1));
        assertThat(event.getEndedAt()).isNull();

        surgeEventService.judge(zoneId, T0.plusMinutes(6), bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, null, null);

        assertThat(event.getEndedAt()).isEqualTo(T0.plusMinutes(6));
    }

    @Test
    @DisplayName("FNC-A-034 증가율이 기준 미만으로 4분 유지되면 열려 있고 5분 유지되면 ended_at이 그 1분 시각")
    void closesAfterHoldWhenRateBelowThreshold() {
        Long zoneId = saveZone("테스트 증가율 종료 구역", "10");
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        Event event = eventRepository.findAll().get(0);

        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(5), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        assertThat(event.getEndedAt()).isNull();

        surgeEventService.judge(zoneId, T0.plusMinutes(6), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        assertThat(event.getEndedAt()).isEqualTo(T0.plusMinutes(6));
    }

    @Test
    @DisplayName("FNC-A-034 기준 미만이 4분 이어지다 다시 기준 이상이 되면 이벤트가 하나로 유지되고 유지 시간은 처음부터 다시 셈")
    void keepsEventWhenRateRecoversBeforeHold() {
        Long zoneId = saveZone("테스트 복귀 구역", "10");
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(5), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(6), bd("180"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(7), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(11), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEndedAt()).isNull();
        assertThat(events.get(0).getBelowSince()).isEqualTo(T0.plusMinutes(7));
    }

    @Test
    @DisplayName("FNC-A-034 종료된 뒤 다시 기준 이상이 되면 새 이벤트를 만듦")
    void opensNewEventAfterPreviousOneEnded() {
        Long zoneId = saveZone("테스트 재급증 구역", "10");
        surgeEventService.judge(zoneId, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0.plusMinutes(6), bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        surgeEventService.judge(zoneId, T0.plusMinutes(7), bd("300"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(Event::getEndedAt).containsExactlyInAnyOrder(T0.plusMinutes(6), null);
        assertThat(events)
                .filteredOn(event -> event.getEndedAt() == null)
                .singleElement()
                .satisfies(open -> assertThat(open.getStartedAt()).isEqualTo(T0.plusMinutes(7)));
    }

    @Test
    @DisplayName("FNC-A-034 열린 이벤트가 없을 때 기준 미만이면 아무것도 하지 않음")
    void doesNothingWhenNoOpenEventAndRateLow() {
        Long zoneId = saveZone("테스트 평시 구역", "10");

        surgeEventService.judge(zoneId, T0, bd("110"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("FNC-A-034 한 구역의 이벤트는 다른 구역의 판정에 영향을 주지 않음")
    void zonesAreIndependent() {
        Long zoneA = saveZone("테스트 A 구역", "10");
        Long zoneB = saveZone("테스트 B 구역", "10");

        surgeEventService.judge(zoneA, T0, bd("200"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneB, T0, bd("100"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneB, T0.plusMinutes(6), bd("100"), bd(ENOUGH_DENSITY), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getZoneId()).isEqualTo(zoneA);
        assertThat(events.get(0).getBelowSince()).isNull();
        assertThat(events.get(0).getEndedAt()).isNull();
    }

    @Test
    @DisplayName("FNC-A-034 같은 구역에서 혼잡 이벤트와 급증 이벤트는 따로 열리고 서로의 종료에 영향을 주지 않음")
    void congestionAndSurgeEventsAreIndependent() {
        Long zoneId = saveZone("테스트 두 종류 구역", "100");

        // 면적 100㎡: 200명이면 밀집도 2.0(위험), 100명 → 200명은 증가율 100%
        congestionEventService.judge(zoneId, T0, bd("200"), bd("2.0000"), FULL_SAMPLE_SEC);
        surgeEventService.judge(zoneId, T0, bd("200"), bd("2.0000"), FULL_SAMPLE_SEC, bd("100"), FULL_SAMPLE_SEC);
        // 증가율만 기준 아래로 내려간다(혼잡은 그대로 위험)
        surgeEventService.judge(zoneId, T0.plusMinutes(1), bd("210"), bd("2.1000"), FULL_SAMPLE_SEC, bd("200"), FULL_SAMPLE_SEC);
        congestionEventService.judge(zoneId, T0.plusMinutes(1), bd("210"), bd("2.1000"), FULL_SAMPLE_SEC);

        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(Event::getType).containsExactlyInAnyOrder(EventType.CONGESTION, EventType.SURGE);
        assertThat(events).filteredOn(event -> event.getType() == EventType.CONGESTION)
                .singleElement()
                .satisfies(congestion -> assertThat(congestion.getBelowSince()).isNull());
        assertThat(events).filteredOn(event -> event.getType() == EventType.SURGE)
                .singleElement()
                .satisfies(surge -> assertThat(surge.getBelowSince()).isEqualTo(T0.plusMinutes(1)));
    }

    @Test
    @DisplayName("FNC-A-034 1분 마감(MinuteStatService.closeEndedMinutes)으로 통계를 저장하면 10분 전 행과 비교해 이벤트가 만들어짐")
    void closingMinuteCreatesSurgeEvent() {
        // 면적 50㎡: 10분 전 20명 → 지금 40명(증가율 100%, 밀집도 0.8)
        Long zoneId = saveZone("테스트 마감 구역", "50");
        insertMinuteStat(zoneId, T0.minusMinutes(10), 20, 0.4);
        for (int second = 0; second < MIN_SAMPLE_SEC; second++) {
            minuteAccumulatorStore.add(
                    zoneId,
                    T0.plusSeconds(second),
                    new SecondResult(40, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
        }

        minuteStatService.closeEndedMinutes(T0.plusMinutes(1).plusSeconds(5));

        List<Event> surges =
                eventRepository.findAll().stream().filter(e -> e.getType() == EventType.SURGE).toList();
        assertThat(surges).hasSize(1);
        assertThat(surges.get(0).getZoneId()).isEqualTo(zoneId);
        assertThat(surges.get(0).getStartedAt()).isEqualTo(T0);
        assertThat(surges.get(0).getPeakValue()).isEqualByComparingTo("100.0000");
        assertThat(surges.get(0).getPeakCount()).isEqualTo(40);
    }

    @Test
    @DisplayName("FNC-A-034 10분 전 행이 없는 구역은 1분 마감으로 저장해도 급증 이벤트를 만들지 않음")
    void closingMinuteWithoutBaselineCreatesNoSurgeEvent() {
        Long zoneId = saveZone("테스트 마감 기준 없음 구역", "50");
        for (int second = 0; second < MIN_SAMPLE_SEC; second++) {
            minuteAccumulatorStore.add(
                    zoneId,
                    T0.plusSeconds(second),
                    new SecondResult(40, DirectionCounts.ZERO, DirectionCounts.ZERO, List.of()));
        }

        minuteStatService.closeEndedMinutes(T0.plusMinutes(1).plusSeconds(5));

        assertThat(eventRepository.findAll()).extracting(Event::getType).doesNotContain(EventType.SURGE);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private void insertMinuteStat(Long zoneId, LocalDateTime statAt, int count, double density) {
        jdbcTemplate.update(
                "INSERT INTO zone_stat_minute (zone_id, stat_at, count_avg, count_max, count_min, density, sample_sec)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                zoneId, statAt, count, count, count, density, FULL_SAMPLE_SEC);
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
