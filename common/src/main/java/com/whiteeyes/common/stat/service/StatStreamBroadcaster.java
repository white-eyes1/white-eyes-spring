package com.whiteeyes.common.stat.service;

import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.NowStreamEvent;
import com.whiteeyes.common.stat.dto.StatStreamEvent;
import com.whiteeyes.common.stat.entity.ZoneNowCount;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
 *
 * <p>실제 전송은 구독자마다 보내기 풀에서 한다. 소켓을 읽지 않는 구독자 하나가 쓰기에서 막혀도(Tomcat 기본 60초) 확인 작업과
 * 다른 구독자의 전송은 멈추지 않는다. 한 구독자의 앞 전송이 아직 끝나지 않았으면 그 구독자에게만 쌓아 두며, 통계는 가장
 * 최근 것 하나만 남기고(다음 분이 이전 분을 대신한다) 하트비트는 버린다(다음 하트비트가 대신한다).
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
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    // 보내는 건의 종류. 통계·현재 인원은 앞 전송이 길어지면 종류별로 최신 하나만 남기고, 하트비트는 버린다
    private enum Kind {
        STAT,
        NOW,
        HEARTBEAT
    }

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final RegionQueryService regionQueryService;
    private final LevelService levelService;
    private final NowCountReader nowCountReader;
    private final JsonMapper jsonMapper;
    // 테스트가 켜진 컨텍스트마다 5초 조회를 돌려 다른 테스트의 쿼리 수를 어지럽히지 않도록 기본은 꺼 두고, 앱 설정(application.yml)에서 켠다
    private final boolean autoPoll;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;
    // 구독자별 전송용. 막힌 구독자 수만큼만 스레드가 늘고, 놀면 60초 뒤 정리된다. 막힌 쓰기는 Tomcat 쓰기 제한(60초)에서
    // 끊기고, 연결 수는 서버 접속 제한이 이미 막으므로 구독자 수 상한은 따로 두지 않는다(명세에도 상한 오류가 없다)
    private final ExecutorService sendPool = Executors.newCachedThreadPool(new SendThreadFactory());
    // 마지막으로 보낸(또는 시작할 때 이미 있던) 분. 시작 전에 저장된 통계를 새 분으로 착각해 보내지 않는다
    private LocalDateTime lastSeenStatAt;
    private boolean initialized;

    public StatStreamBroadcaster(
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            RegionQueryService regionQueryService,
            LevelService levelService,
            NowCountReader nowCountReader,
            JsonMapper jsonMapper,
            @Value("${stats.stream.auto-poll:false}") boolean autoPoll) {
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.regionQueryService = regionQueryService;
        this.levelService = levelService;
        this.nowCountReader = nowCountReader;
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
        executor.scheduleWithFixedDelay(this::safePollNow, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::safeHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        stop();
        sendPool.shutdownNow();
    }

    // 5초 확인 작업을 멈추고 연결을 모두 닫는다. 보내기 풀은 남겨 두어 테스트가 이어서 쓸 수 있다
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
        return subscribe(regionId, new SseEmitter(0L));
    }

    // 시험에서 일부러 막히는 emitter를 넣을 수 있게 열어 둔다
    public SseEmitter subscribe(Long regionId, SseEmitter emitter) {
        if (regionId != null) {
            regionQueryService.getRegion(regionId);
        }
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

    private void safePollNow() {
        try {
            pollNowOnce(LocalDateTime.now(KST_ZONE));
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 5초에 다시 읽어야 하므로 작업이 죽지 않게 한다
            log.warn("실시간 현재 인원 확인 중 오류가 났습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }

    /**
     * 5초마다: 신선한 현재 인원(zone_now_count)을 전체 구역 한 번에 읽어 구독자마다 자기 지역 소속분만 event: now 로 보낸다
     * (FNC-C-022). 구독자가 없으면 읽지 않고, 보낼 구역이 없는 구독자에게는 보내지 않는다.
     *
     * @param now KST 현재 시각
     * @return 이번에 보낸 구역 수(구독자가 같은 구역을 중복해서 받아도 한 번만 센다). 읽지 않았거나 신선한 값이 없으면 0
     */
    public synchronized int pollNowOnce(LocalDateTime now) {
        if (subscribers.isEmpty()) {
            return 0;
        }
        List<ZoneNowCount> counts = nowCountReader.findAllFresh(now);
        if (counts.isEmpty()) {
            return 0;
        }
        Map<Long, Set<Long>> zoneIdsByRegion = new HashMap<>();
        for (Subscriber subscriber : subscribers) {
            Set<Long> zoneIds = subscriber.regionId() == null
                    ? null
                    : zoneIdsByRegion.computeIfAbsent(subscriber.regionId(), this::findZoneIds);
            List<NowStreamEvent.Item> items = counts.stream()
                    .filter(count -> zoneIds == null || zoneIds.contains(count.getZoneId()))
                    .map(count -> new NowStreamEvent.Item(
                            count.getZoneId(), count.getPersonCount(), count.getUpdatedAt().atOffset(KST_OFFSET)))
                    .toList();
            if (!items.isEmpty()) {
                String json = jsonMapper.writeValueAsString(new NowStreamEvent(items));
                dispatch(subscriber, () -> SseEmitter.event().name("now").data(json), Kind.NOW);
            }
        }
        return counts.size();
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
        String json = jsonMapper.writeValueAsString(payload);
        dispatch(subscriber, () -> SseEmitter.event().name("stat").data(json), Kind.STAT);
    }

    private void safeHeartbeat() {
        try {
            sendHeartbeat();
        } catch (RuntimeException e) {
            // 한 번 실패해도 30초 뒤 다시 보내야 하므로 작업이 죽지 않게 한다
            log.warn("실시간 통계 하트비트 중 오류가 났습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }

    private void sendHeartbeat() {
        for (Subscriber subscriber : subscribers) {
            dispatch(subscriber, () -> SseEmitter.event().comment("keepalive"), Kind.HEARTBEAT);
        }
    }

    /**
     * 구독자의 전송 순서에 한 건을 넣는다. 앞 전송이 진행 중이면 통계·현재 인원은 종류별 대기 칸에 남기고(이미 있던 같은 종류
     * 대기분은 대신해 버린다. 종류가 다르면 서로 대신하지 않는다) 하트비트는 버린다. 이 구독자가 막혀 있어도 다른 구독자와
     * 호출한 스레드는 기다리지 않는다.
     */
    private void dispatch(Subscriber subscriber, Supplier<SseEmitter.SseEventBuilder> event, Kind kind) {
        synchronized (subscriber) {
            if (subscriber.sending) {
                if (kind == Kind.STAT) {
                    subscriber.pendingStat = event;
                } else if (kind == Kind.NOW) {
                    subscriber.pendingNow = event;
                }
                return;
            }
            subscriber.sending = true;
        }
        try {
            sendPool.execute(() -> drain(subscriber, event));
        } catch (RejectedExecutionException e) {
            // 앱을 내리는 중이다
            synchronized (subscriber) {
                subscriber.sending = false;
            }
        }
    }

    // 보내기 스레드에서: 첫 건을 보내고, 그 사이 쌓인 대기분이 있으면 이어서 보낸다
    private void drain(Subscriber subscriber, Supplier<SseEmitter.SseEventBuilder> first) {
        Supplier<SseEmitter.SseEventBuilder> next = first;
        while (next != null) {
            try {
                subscriber.emitter().send(next.get());
            } catch (IOException | IllegalStateException e) {
                // 이미 끊긴 연결이다. 목록에서 빼고 넘어간다
                abandon(subscriber);
                return;
            } catch (RuntimeException e) {
                // 한 구독자의 예상 못한 오류가 이 스레드와 다른 구독자에게 번지지 않게 한다
                log.warn("실시간 통계 전송 중 오류가 나 이 연결을 닫습니다.", e);
                abandon(subscriber);
                return;
            }
            synchronized (subscriber) {
                // 1분 통계가 먼저 간다. 현재 인원은 5초 뒤 다시 오므로 밀려도 괜찮다
                if (subscriber.pendingStat != null) {
                    next = subscriber.pendingStat;
                    subscriber.pendingStat = null;
                } else {
                    next = subscriber.pendingNow;
                    subscriber.pendingNow = null;
                }
                if (next == null) {
                    subscriber.sending = false;
                }
            }
        }
    }

    private void abandon(Subscriber subscriber) {
        subscribers.remove(subscriber);
        synchronized (subscriber) {
            subscriber.pendingStat = null;
            subscriber.pendingNow = null;
            subscriber.sending = false;
        }
    }

    // 테스트에서 하트비트를 바로 확인할 수 있게 열어 둔다
    public void sendHeartbeatNow() {
        sendHeartbeat();
    }

    private static final class Subscriber {
        private final Long regionId;
        private final SseEmitter emitter;
        // 아래 둘은 이 객체의 잠금 안에서만 바꾼다
        private boolean sending;
        private Supplier<SseEmitter.SseEventBuilder> pendingStat;
        private Supplier<SseEmitter.SseEventBuilder> pendingNow;

        Subscriber(Long regionId, SseEmitter emitter) {
            this.regionId = regionId;
            this.emitter = emitter;
        }

        Long regionId() {
            return regionId;
        }

        SseEmitter emitter() {
            return emitter;
        }
    }

    private static final class SendThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "stat-stream-send-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
