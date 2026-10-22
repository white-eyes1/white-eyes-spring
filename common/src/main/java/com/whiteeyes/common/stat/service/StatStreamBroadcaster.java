package com.whiteeyes.common.stat.service;

import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.StatStreamEvent;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실시간 통계 갱신(FNC-C-022). 새 1분 통계가 저장되면 연결된 브라우저에 SSE(event: stat)로 보낸다.
 *
 * <p>앱마다 백그라운드 작업 1개가 {@link #POLL_SECONDS}초마다 zone_stat_minute의 최신 stat_at만 확인하고, 새 분이 생기면
 * 그 분의 전체 구역 통계를 한 번 조회해 구독자마다 자기 지역 소속분만 골라 보낸다. 그래서 DB 조회 횟수는
 * 접속자 수와 무관하다(지역별 소속 구역을 찾는 조회만 구독 중인 지역 수만큼 늘어난다).
 * 30초마다 주석 줄을 보내 nginx·브라우저가 연결을 끊지 않게 한다.
 */
@Slf4j
@Service
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 SSE도 5초 확인도 필요 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StatStreamBroadcaster {

    static final int POLL_SECONDS = 5;
    static final int HEARTBEAT_SECONDS = 30;
    static final long RECONNECT_MILLIS = 5000;
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final RegionQueryService regionQueryService;
    private final LevelService levelService;
    private final JsonMapper jsonMapper;
    // 테스트가 켜진 컨텍스트마다 5초 조회를 돌려 다른 테스트의 쿼리 수를 어지럽히지 않도록 기본은 꺼 두고, 앱 설정(application.yml)에서 켠다
    private final boolean autoPoll;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;
    // 마지막으로 보낸(또는 시작할 때 이미 있던) 분. 시작 전에 저장된 통계를 새 분으로 착각해 보내지 않는다
    private LocalDateTime lastSeenStatAt;
    private boolean initialized;

    public StatStreamBroadcaster(
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            RegionQueryService regionQueryService,
            LevelService levelService,
            JsonMapper jsonMapper,
            @Value("${stats.stream.auto-poll:false}") boolean autoPoll) {
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.regionQueryService = regionQueryService;
        this.levelService = levelService;
        this.jsonMapper = jsonMapper;
        this.autoPoll = autoPoll;
    }

    @PostConstruct
    void start() {
        if (!autoPoll) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stat-stream");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safePoll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        subscribers.forEach(subscriber -> subscriber.emitter().complete());
        subscribers.clear();
    }

    /**
     * 구독을 시작한다.
     *
     * @param regionId 구독 지역(없으면 전체)
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    public SseEmitter subscribe(Long regionId) {
        if (regionId != null) {
            regionQueryService.getRegion(regionId);
        }
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(regionId, emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> subscribers.remove(subscriber));
        try {
            // 연결이 끊기면 브라우저가 5초 뒤 다시 연결한다(retry 5000)
            emitter.send(SseEmitter.event().reconnectTime(RECONNECT_MILLIS).comment("connected"));
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(subscriber);
        }
        return emitter;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private void safePoll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 5초에 다시 확인해야 하므로 작업이 죽지 않게 한다
            log.warn("실시간 통계 확인 중 오류가 났습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }

    /**
     * 최신 통계 시각을 확인해 새 분이면 구독자에게 보낸다. 5초마다 도는 작업이 부르며, 테스트가 직접 부르기도 한다.
     *
     * @return 이번에 새 분으로 보낸 통계 시각. 새 분이 없으면 null
     */
    public synchronized LocalDateTime pollOnce() {
        LocalDateTime latest = zoneStatMinuteRepository.findLatestStatAt();
        if (!initialized) {
            initialized = true;
            lastSeenStatAt = latest;
            return null;
        }
        if (latest == null || (lastSeenStatAt != null && !latest.isAfter(lastSeenStatAt))) {
            return null;
        }
        lastSeenStatAt = latest;
        if (subscribers.isEmpty()) {
            return latest;
        }
        // 그 분의 전체 구역 통계를 한 번만 조회한다
        List<ZoneStatMinute> stats = zoneStatMinuteRepository.findByStatAt(latest);
        Map<Long, Set<Long>> zoneIdsByRegion = new HashMap<>();
        for (Subscriber subscriber : subscribers) {
            Set<Long> zoneIds = subscriber.regionId() == null
                    ? null
                    : zoneIdsByRegion.computeIfAbsent(subscriber.regionId(), this::findZoneIds);
            List<StatStreamEvent.Item> items = stats.stream()
                    .filter(stat -> zoneIds == null || zoneIds.contains(stat.getZoneId()))
                    .map(stat -> new StatStreamEvent.Item(
                            stat.getZoneId(),
                            stat.getCountAvg(),
                            stat.getDensity(),
                            levelService
                                    .judgeLevel(LevelTarget.zone(stat.getZoneId()), stat.getDensity())
                                    .getLevel()))
                    .toList();
            if (!items.isEmpty()) {
                send(subscriber, new StatStreamEvent(latest.atOffset(KST_OFFSET), items));
            }
        }
        return latest;
    }

    // 지역(시·도, 시·군·구, 읍·면·동) 아래 삭제되지 않은 구역 번호. 지역이 그 사이 사라졌으면 빈 집합
    private Set<Long> findZoneIds(Long regionId) {
        try {
            Region region = regionQueryService.getRegion(regionId);
            List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream().map(Region::getId).toList();
            if (dongIds.isEmpty()) {
                return Set.of();
            }
            return regionQueryService.findZonesInDongs(dongIds).stream().map(Zone::getId).collect(Collectors.toSet());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private void send(Subscriber subscriber, StatStreamEvent payload) {
        try {
            subscriber.emitter()
                    .send(SseEmitter.event().name("stat").data(jsonMapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException e) {
            // 이미 끊긴 연결이다. 목록에서 빼고 넘어간다
            subscribers.remove(subscriber);
        }
    }

    private void sendHeartbeat() {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(subscriber);
            }
        }
    }

    // 테스트에서 하트비트를 바로 확인할 수 있게 열어 둔다
    public void sendHeartbeatNow() {
        sendHeartbeat();
    }

    private record Subscriber(Long regionId, SseEmitter emitter) {}
}
