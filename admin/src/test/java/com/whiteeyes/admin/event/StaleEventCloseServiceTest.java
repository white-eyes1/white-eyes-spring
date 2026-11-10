package com.whiteeyes.admin.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.event.service.CongestionEventService;
import com.whiteeyes.admin.event.service.StaleEventCloseService;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.stat.service.ZoneStatQueryService;

/**
 * 결과 끊김 종료(FNC-A-033 5번, FNC-A-034 5번). 기준은 기본값(최소 30초, 종료 유지 5분)이다.
 * 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에 테스트 시각은 먼 미래(2099년)로 둔다. 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class StaleEventCloseServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 1, 10, 0);
    private static final short FULL_SAMPLE_SEC = 60;
    private static final short SHORT_SAMPLE_SEC = 29;

    @Autowired
    private StaleEventCloseService staleEventCloseService;

    @Autowired
    private CongestionEventService congestionEventService;

    @Autowired
    private ZoneStatQueryService zoneStatQueryService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 앱 시작 시각이 시험 시각보다 한참 앞이라 시작 시각 규칙이 걸리지 않는 것을 기본으로 한다
    @BeforeEach
    void startLongBefore() {
        staleEventCloseService.markStarted(T0.minusDays(1));
    }

    @AfterEach
    void restoreStartedAt() {
        staleEventCloseService.markStarted(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("FNC-A-033·034 결과가 없는 분이 5분 이어지면 혼잡·급증 이벤트가 마지막 판정 가능 분 +1분으로 종료")
    void closesCongestionAndSurgeAfterHoldWithoutResults() {
        Long zoneId = saveZone("테스트 끊김 구역");
        saveStat(zoneId, T0, FULL_SAMPLE_SEC);
        Long congestionId = openCongestion(zoneId, T0);
        Long surgeId = openSurge(zoneId, T0);

        staleEventCloseService.closeStale(T0.plusMinutes(5));

        assertThat(endedAt(congestionId)).isEqualTo(T0.plusMinutes(1));
        assertThat(endedAt(surgeId)).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033·034 결과가 없는 분이 4분이면 아직 닫지 않음")
    void keepsOpenBeforeHold() {
        Long zoneId = saveZone("테스트 경계 구역");
        saveStat(zoneId, T0, FULL_SAMPLE_SEC);
        Long congestionId = openCongestion(zoneId, T0);
        Long surgeId = openSurge(zoneId, T0);

        staleEventCloseService.closeStale(T0.plusMinutes(4));

        assertThat(endedAt(congestionId)).isNull();
        assertThat(endedAt(surgeId)).isNull();
    }

    @Test
    @DisplayName("FNC-A-033 sample_sec가 30 미만인 행만 이어져도 끊김으로 보고 마지막 판정 가능 분 +1분으로 종료")
    void treatsShortSampleMinutesAsGap() {
        Long zoneId = saveZone("테스트 표본 구역");
        saveStat(zoneId, T0, FULL_SAMPLE_SEC);
        for (int minute = 1; minute <= 5; minute++) {
            saveStat(zoneId, T0.plusMinutes(minute), SHORT_SAMPLE_SEC);
        }
        Long eventId = openCongestion(zoneId, T0);

        staleEventCloseService.closeStale(T0.plusMinutes(4));
        assertThat(endedAt(eventId)).isNull();

        staleEventCloseService.closeStale(T0.plusMinutes(5));
        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033 앱 시작 뒤 5분 전에는 닫지 않고, 5분 뒤 마지막 판정 가능 분 +1분으로 종료")
    void countsGapFromAppStart() {
        Long zoneId = saveZone("테스트 재시작 구역");
        saveStat(zoneId, T0, FULL_SAMPLE_SEC);
        Long eventId = openCongestion(zoneId, T0);
        // 앱이 한참 꺼져 있다가 켜졌다(마지막 판정 가능 분은 오래전)
        LocalDateTime appStart = T0.plusHours(2);
        staleEventCloseService.markStarted(appStart.plusSeconds(30));

        staleEventCloseService.closeStale(appStart.plusMinutes(4));
        assertThat(endedAt(eventId)).isNull();

        staleEventCloseService.closeStale(appStart.plusMinutes(5));
        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033 결과가 계속 들어오는 구역의 이벤트는 건드리지 않음")
    void leavesZoneWithContinuingResults() {
        Long zoneId = saveZone("테스트 계속 구역");
        for (int minute = 0; minute <= 10; minute++) {
            saveStat(zoneId, T0.plusMinutes(minute), FULL_SAMPLE_SEC);
        }
        Long eventId = openCongestion(zoneId, T0);

        staleEventCloseService.closeStale(T0.plusMinutes(10));

        assertThat(endedAt(eventId)).isNull();
    }

    @Test
    @DisplayName("FNC-A-033 정상 종료(below_since)로 닫힌 이벤트는 다시 닫지 않음")
    void doesNotCloseAgainEventClosedNormally() {
        Long zoneId = saveZone("테스트 정상종료 구역");
        judge(zoneId, 0, "0.80");
        judge(zoneId, 1, "0.50");
        judge(zoneId, 6, "0.50");
        Long eventId = eventRepository.findAll().get(0).getId();
        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(6));

        staleEventCloseService.closeStale(T0.plusMinutes(30));

        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(6));
    }

    @Test
    @DisplayName("FNC-A-033 마지막 판정 가능 분이 없으면 started_at +1분으로 종료")
    void closesAtStartedAtPlusOneWhenNoJudgeableMinute() {
        Long zoneId = saveZone("테스트 행없음 구역");
        Long eventId = openCongestion(zoneId, T0);

        staleEventCloseService.closeStale(T0.plusMinutes(5));

        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033 마지막 판정 가능 분이 started_at보다 앞이면 started_at +1분으로 종료")
    void closesAtStartedAtPlusOneWhenLastJudgeableIsBeforeStart() {
        Long zoneId = saveZone("테스트 이전행 구역");
        saveStat(zoneId, T0.minusMinutes(10), FULL_SAMPLE_SEC);
        Long eventId = openCongestion(zoneId, T0);

        staleEventCloseService.closeStale(T0.plusMinutes(5));

        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033 삭제된 구역의 열린 이벤트도 닫힘")
    void closesEventOfDeletedZone() {
        Long zoneId = saveZone("테스트 삭제 구역");
        saveStat(zoneId, T0, FULL_SAMPLE_SEC);
        Long eventId = openCongestion(zoneId, T0);
        jdbcTemplate.update("UPDATE zone SET deleted_at = ? WHERE id = ?", T0.plusMinutes(2), zoneId);

        staleEventCloseService.closeStale(T0.plusMinutes(5));

        assertThat(endedAt(eventId)).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("FNC-A-033 조회 하한(가장 이른 started_at) 앞의 판정 가능 행은 마지막 판정 가능 분 조회에 잡히지 않음")
    void lastJudgeableQueryIgnoresRowsBeforeLowerBound() {
        Long beforeOnlyZone = saveZone("테스트 하한앞 구역");
        Long insideZone = saveZone("테스트 하한안 구역");
        saveStat(beforeOnlyZone, T0.minusMinutes(1), FULL_SAMPLE_SEC);
        saveStat(insideZone, T0.minusMinutes(1), FULL_SAMPLE_SEC);
        saveStat(insideZone, T0, FULL_SAMPLE_SEC);
        saveStat(insideZone, T0.plusMinutes(1), SHORT_SAMPLE_SEC);

        Map<Long, LocalDateTime> result =
                zoneStatQueryService.findLastJudgeableMinuteByZone(List.of(beforeOnlyZone, insideZone), T0, 30);

        assertThat(result).doesNotContainKey(beforeOnlyZone);
        assertThat(result).containsEntry(insideZone, T0);
    }

    private Long openCongestion(Long zoneId, LocalDateTime startedAt) {
        Long id = eventRepository
                .save(Event.openCongestion(zoneId, startedAt, 2, new BigDecimal("0.8000"), 1, "{}"))
                .getId();
        entityManager.flush();
        return id;
    }

    private Long openSurge(Long zoneId, LocalDateTime startedAt) {
        Long id = eventRepository
                .save(Event.openSurge(zoneId, startedAt, new BigDecimal("60.0000"), 1, "{}"))
                .getId();
        entityManager.flush();
        return id;
    }

    // 면적 1㎡ 구역에서는 인원 = 밀집도
    private void judge(Long zoneId, int minuteOffset, String density) {
        congestionEventService.judge(
                zoneId,
                T0.plusMinutes(minuteOffset),
                new BigDecimal(density),
                new BigDecimal(density),
                FULL_SAMPLE_SEC);
    }

    private void saveStat(Long zoneId, LocalDateTime statAt, short sampleSec) {
        zoneStatMinuteRepository.upsert(ZoneStatMinute.builder()
                .zoneId(zoneId)
                .statAt(statAt)
                .countAvg(new BigDecimal("1.00"))
                .countMax(1)
                .countMin(0)
                .density(new BigDecimal("1.0000"))
                .sampleSec(sampleSec)
                .build());
    }

    // 통계 upsert가 영속성 컨텍스트를 비우므로 매번 다시 읽는다
    private LocalDateTime endedAt(Long eventId) {
        entityManager.flush();
        entityManager.clear();
        return eventRepository.findById(eventId).orElseThrow().getEndedAt();
    }

    // zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 구역마다 다른 동 코드를 쓴다
    private Long saveZone(String name) {
        String code = String.valueOf(99980000 + Math.floorMod(name.hashCode(), 9999));
        jdbcTemplate.update(
                "INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                        + " VALUES ('EMD', ?, ?, '{}', 37.600000, 127.002000)",
                code,
                name + " 동");
        Long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
        Zone zone = new Zone(
                dongId, name, new BigDecimal("1"), "{}", new BigDecimal("37.600000"), new BigDecimal("127.002000"));
        return zoneRepository.save(zone).getId();
    }
}
