package com.whiteeyes.common.stat.service;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.level.service.LevelService;
import com.whiteeyes.common.level.service.LevelTarget;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.service.RegionQueryService;
import com.whiteeyes.common.stat.dto.NowStreamEvent;
import com.whiteeyes.common.stat.dto.StatStreamEvent;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.stat.service.NowCountSource.NowCount;

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
public class StatStreamBroadcaster {

    // 새 분·현재 인원을 확인하는 주기(초)
    static final int POLL_SECONDS = 5;
    // 하트비트(연결 유지용 주석 줄)를 보내는 주기(초). 중간 프록시나 브라우저가 조용한 연결을 끊지 않게 한다
    static final int HEARTBEAT_SECONDS = 30;
    // 연결이 끊겼을 때 브라우저(EventSource)가 다시 연결을 시도하기까지 기다리는 시간(밀리초)
    static final long RECONNECT_MILLIS = 5000;
    // 응답의 시각에 붙이는 한국 시간 오프셋(+09:00)
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // '지금'을 한국 시간으로 구할 때 쓴다
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    // 보내는 건의 종류. 통계·현재 인원은 앞 전송이 길어지면 종류별로 최신 하나만 남기고, 하트비트는 버린다
    private enum Kind {
        STAT,
        NOW,
        HEARTBEAT
    }

    // 최신 1분 통계 시각을 확인하고 새 분의 통계를 읽는다
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    // 구독 지역 아래 구역 번호를 찾고, 구독할 때 지역이 있는지 확인한다
    private final RegionQueryService regionQueryService;
    // 구역의 밀집도를 혼잡 단계(1~3)로 바꿔 이벤트에 담는다
    private final LevelService levelService;
    // 관리자 앱에만 있다. 사용자 앱은 빈이 없어 event: now 를 보내지 않는다(결정 0018)
    private final ObjectProvider<NowCountSource> nowCountSource;
    // 이벤트 본문을 JSON 문자열로 바꾼다
    private final JsonMapper jsonMapper;
    // 테스트가 켜진 컨텍스트마다 5초 조회를 돌려 다른 테스트의 쿼리 수를 어지럽히지 않도록 기본은 꺼 두고, 앱 설정(application.yml)에서 켠다
    private final boolean autoPoll;

    // 구독자 목록. 전송·확인 스레드는 순회만 하고 연결·종료 시에만 바뀌므로, 읽기가 훨씬 많은 이 상황에 맞는 CopyOnWriteArrayList를 쓴다(순회 중 수정해도 예외가 나지 않는다)
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    // 5초 확인·하트비트용. 작업은 autoPoll이 켜졌을 때만 예약한다
    // 스레드 하나로 확인 작업들을 차례로 돌린다. 데몬 스레드라 앱 종료를 막지 않는다
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stat-stream");
        thread.setDaemon(true);
        return thread;
    });
    // 구독자별 전송용. 막힌 구독자 수만큼만 스레드가 늘고, 놀면 60초 뒤 정리된다. 막힌 쓰기는 Tomcat 쓰기 제한(60초)에서
    // 끊기고, 연결 수는 서버 접속 제한이 이미 막으므로 구독자 수 상한은 따로 두지 않는다(명세에도 상한 오류가 없다)
    // 실제 전송을 맡는 풀. 구독자 한 명의 쓰기가 막혀도 확인 작업과 다른 구독자의 전송이 영향을 받지 않게 분리했다
    private final ExecutorService sendPool = Executors.newCachedThreadPool(new SendThreadFactory());
    // 마지막으로 보낸(또는 시작할 때 이미 있던) 분. 시작 전에 저장된 통계를 새 분으로 착각해 보내지 않는다.
    // 빈 필드는 final로 두므로(code-conventions.md 3-3) 바뀌는 값은 Atomic으로 감싼다. 읽고 쓰는 곳은 pollOnce(synchronized)뿐이다
    // 마지막으로 확인한 최신 통계 시각. 이보다 늦은 시각이 나타나면 새 분이다
    private final AtomicReference<LocalDateTime> lastSeenStatAt = new AtomicReference<>();
    // 첫 확인을 했는지. 첫 확인에서는 기준 시각만 기억하고 아무것도 보내지 않는다
    private final AtomicBoolean initialized = new AtomicBoolean();

    /** autoPoll이 true일 때만 주기 작업을 예약한다(설정 stats.stream.auto-poll). 기본값은 false라 테스트 컨텍스트에서는 돌지 않는다 */
    public StatStreamBroadcaster(
            ZoneStatMinuteRepository zoneStatMinuteRepository,
            RegionQueryService regionQueryService,
            LevelService levelService,
            ObjectProvider<NowCountSource> nowCountSource,
            JsonMapper jsonMapper,
            @Value("${stats.stream.auto-poll:false}") boolean autoPoll) {
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.regionQueryService = regionQueryService;
        this.levelService = levelService;
        this.nowCountSource = nowCountSource;
        this.jsonMapper = jsonMapper;
        this.autoPoll = autoPoll;
    }

    // 빈이 만들어진 뒤 호출되는 시작 지점. 서로 다른 세 작업을 고정 지연으로 예약한다(앞 작업이 끝난 뒤 지연이 지나야 다음이 시작되어 겹쳐 실행되지 않는다)
    @PostConstruct
    void start() {
        if (!autoPoll) {
            return;
        }
        // 새 1분 통계 확인(event: stat)
        executor.scheduleWithFixedDelay(this::safePoll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        // 현재 인원 전송(event: now)
        executor.scheduleWithFixedDelay(this::safePollNow, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        // 연결 유지용 하트비트
        executor.scheduleWithFixedDelay(this::safeHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    // 앱이 내려갈 때 확인 작업과 보내기 풀을 함께 정리한다
    @PreDestroy
    void shutdown() {
        stop();
        sendPool.shutdownNow();
    }

    // 5초 확인 작업을 멈추고 연결을 모두 닫는다. 보내기 풀은 남겨 두어 테스트가 이어서 쓸 수 있다
    /** 주기 작업을 멈추고 모든 구독 연결을 닫는다 */
    public void stop() {
        executor.shutdownNow();
        subscribers.forEach(subscriber -> subscriber.emitter().complete());
        subscribers.clear();
    }

    /**
     * 구독을 시작한다.
     *
     * @param regionId 구독 지역(없으면 전체)
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     */
    // 타임아웃 0은 서버 쪽에서 시간 제한 없이 연결을 유지한다는 뜻이다(끊기는 것은 클라이언트나 쓰기 실패 때뿐)
    public SseEmitter subscribe(Long regionId) {
        return subscribe(regionId, new SseEmitter(0L));
    }

    // 시험에서 일부러 막히는 emitter를 넣을 수 있게 열어 둔다
    public SseEmitter subscribe(Long regionId, SseEmitter emitter) {
        // 지역을 지정했으면 있는지 먼저 확인한다(없으면 404). 전체 구독(null)은 확인할 것이 없다
        if (regionId != null) {
            regionQueryService.getRegion(regionId);
        }
        // 연결 하나를 구독자로 등록한다. 완료·오류·시간 초과 때 목록에서 빠지도록 아래에서 콜백을 단다
        Subscriber subscriber = new Subscriber(regionId, emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        // 시간 초과가 나면 연결을 정상 종료로 닫는다(그러면 위의 onCompletion이 목록에서 뺀다)
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> subscribers.remove(subscriber));
        try {
            // 연결이 끊기면 브라우저가 5초 뒤 다시 연결한다(retry 5000)
            // 연결 직후 첫 응답을 보내 응답 헤더가 곧바로 브라우저에 가게 한다(프록시가 응답을 쥐고 있지 않도록)
            emitter.send(SseEmitter.event().reconnectTime(RECONNECT_MILLIS).comment("connected"));
        } catch (IOException | IllegalStateException e) {
            // 첫 전송부터 실패하면 이미 끊긴 연결이라 목록에서 뺀다
            subscribers.remove(subscriber);
        }
        return emitter;
    }

    /** 현재 연결된 구독자 수(테스트·점검용) */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** 예약 작업이 예외로 죽지 않게 pollOnce를 감싼다. 예약된 작업이 예외로 끝나면 이후 실행이 취소되기 때문이다 */
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
    // synchronized: 5초 작업과 테스트의 직접 호출이 동시에 돌아 같은 분을 두 번 보내지 않게 한다
    public synchronized LocalDateTime pollOnce() {
        // 전체 구역의 가장 최근 1분 통계 시각 하나만 가볍게 읽는다
        LocalDateTime latest = zoneStatMinuteRepository.findLatestStatAt();
        // 첫 확인: 지금 DB에 있는 분을 기준으로 삼기만 한다. 서버가 켜지기 전에 저장된 통계를 새 분으로 착각해 보내지 않으려는 것이다
        if (!initialized.get()) {
            initialized.set(true);
            // 새 분을 기억한다. 구독자가 없어도 기억해 두어, 나중에 구독이 생겼을 때 이미 지난 분을 보내지 않게 한다
            lastSeenStatAt.set(latest);
            return null;
        }
        LocalDateTime lastSeen = lastSeenStatAt.get();
        // 통계가 아직 없거나, 마지막으로 본 분보다 늦지 않으면 새 분이 아니다
        if (latest == null || (lastSeen != null && !latest.isAfter(lastSeen))) {
            return null;
        }
        lastSeenStatAt.set(latest);
        if (subscribers.isEmpty()) {
            return latest;
        }
        // 그 분의 전체 구역 통계를 한 번만 조회한다
        // 새 분의 전체 구역 통계를 한 번만 읽는다. 구독자가 많아도 DB 조회는 늘지 않는다
        List<ZoneStatMinute> stats = zoneStatMinuteRepository.findByStatAt(latest);
        // 지역 번호 → 소속 구역 번호 집합. 같은 지역을 구독한 사람이 여럿이어도 한 번만 계산하려는 캐시다
        Map<Long, Set<Long>> zoneIdsByRegion = new HashMap<>();
        for (Subscriber subscriber : subscribers) {
            // 전체 구독(null)이면 구역을 거르지 않고, 지역 구독이면 그 지역 소속 구역만 남긴다
            Set<Long> zoneIds = subscriber.regionId() == null
                    ? null
                    : zoneIdsByRegion.computeIfAbsent(subscriber.regionId(), this::findZoneIds);
            List<StatStreamEvent.Item> items = stats.stream()
                    // 구독 지역 소속 구역의 통계만 남긴다(전체 구독이면 모두)
                    .filter(stat -> zoneIds == null || zoneIds.contains(stat.getZoneId()))
                    .map(stat -> new StatStreamEvent.Item(
                            stat.getZoneId(),
                            stat.getCountAvg(),
                            stat.getDensity(),
                            levelService
                                    // 구역의 밀집도를 구역 기준값으로 혼잡 단계로 바꿔 이벤트에 같이 담는다(화면이 색을 바로 바꿀 수 있게)
                                    .judgeLevel(LevelTarget.zone(stat.getZoneId()), stat.getDensity())
                                    .getLevel()))
                    .toList();
            // 이 구독자에게 보낼 구역이 없으면(구독 지역에 새 통계가 없으면) 이벤트를 보내지 않는다
            if (!items.isEmpty()) {
                send(subscriber, new StatStreamEvent(latest.atOffset(KST_OFFSET), items));
            }
        }
        return latest;
    }

    /** 예약 작업이 예외로 죽지 않게 pollNowOnce를 감싼다 */
    private void safePollNow() {
        try {
            pollNowOnce(LocalDateTime.now(KST_ZONE));
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 5초에 다시 읽어야 하므로 작업이 죽지 않게 한다
            log.warn("실시간 현재 인원 확인 중 오류가 났습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }

    /**
     * 5초마다: 신선한 현재 인원을 전체 구역 한 번에 읽어 구독자마다 자기 지역 소속분만 event: now 로 보낸다
     * (FNC-C-022, 관리자 앱만). 값은 메모리 누적기에서 읽어 DB 조회가 없다. 현재 인원을 내는 쪽(NowCountSource)이 없는
     * 사용자 앱이나 구독자가 없으면 읽지 않고, 보낼 구역이 없는 구독자에게는 보내지 않는다.
     *
     * @param now KST 현재 시각
     * @return 이번에 보낸 구역 수(구독자가 같은 구역을 중복해서 받아도 한 번만 센다). 읽지 않았거나 신선한 값이 없으면 0
     */
    public synchronized int pollNowOnce(LocalDateTime now) {
        // 현재 인원을 내는 쪽이 없는 앱(사용자 앱)이거나 구독자가 없으면 읽지 않는다
        NowCountSource source = nowCountSource.getIfAvailable();
        if (source == null || subscribers.isEmpty()) {
            return 0;
        }
        // 신선한 현재 인원을 전체 구역 한 번에 읽는다(메모리에서 읽으므로 DB 부담이 없다)
        List<NowCount> counts = source.findAllFresh(now);
        if (counts.isEmpty()) {
            return 0;
        }
        Map<Long, Set<Long>> zoneIdsByRegion = new HashMap<>();
        for (Subscriber subscriber : subscribers) {
            Set<Long> zoneIds = subscriber.regionId() == null
                    ? null
                    : zoneIdsByRegion.computeIfAbsent(subscriber.regionId(), this::findZoneIds);
            List<NowStreamEvent.Item> items = counts.stream()
                    .filter(count -> zoneIds == null || zoneIds.contains(count.zoneId()))
                    .map(count -> new NowStreamEvent.Item(
                            count.zoneId(), count.personCount(), count.at().atOffset(KST_OFFSET)))
                    .toList();
            if (!items.isEmpty()) {
                // 구독자마다 소속 구역분만 담은 본문을 JSON으로 만든다
                String json = jsonMapper.writeValueAsString(new NowStreamEvent(items));
                // 이벤트를 즉시 만들지 않고 Supplier로 넘긴다. 실제 전송 시점에 만들면 전송이 밀려 대기하는 동안에도 같은 본문을 재사용할 수 있다
                dispatch(subscriber, () -> SseEmitter.event().name("now").data(json), Kind.NOW);
            }
        }
        // 구독자가 몇 명이든 같은 구역은 한 번으로 센 구역 수를 돌려준다(보낸 이벤트 수가 아니다)
        return counts.size();
    }

    // 지역(시·도, 시·군·구, 읍·면·동) 아래 삭제되지 않은 구역 번호. 지역이 그 사이 사라졌으면 빈 집합
    /** 구독 지역 아래 구역 번호 집합을 구한다. 실패해도 구독자 한 명 때문에 전체 전송이 멈추지 않도록 예외를 삼키고 빈 집합을 준다 */
    private Set<Long> findZoneIds(Long regionId) {
        try {
            Region region = regionQueryService.getRegion(regionId);
            List<Long> dongIds = regionQueryService.findEmdsUnder(region).stream()
                    .map(Region::getId)
                    .toList();
            if (dongIds.isEmpty()) {
                return Set.of();
            }
            return regionQueryService.findZonesInDongs(dongIds).stream()
                    .map(Zone::getId)
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** 통계 이벤트(event: stat)를 JSON으로 만들어 전송 순서에 넣는다 */
    private void send(Subscriber subscriber, StatStreamEvent payload) {
        String json = jsonMapper.writeValueAsString(payload);
        dispatch(subscriber, () -> SseEmitter.event().name("stat").data(json), Kind.STAT);
    }

    /** 예약 작업이 예외로 죽지 않게 sendHeartbeat를 감싼다 */
    private void safeHeartbeat() {
        try {
            sendHeartbeat();
        } catch (RuntimeException e) {
            // 한 번 실패해도 30초 뒤 다시 보내야 하므로 작업이 죽지 않게 한다
            log.warn("실시간 통계 하트비트 중 오류가 났습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }

    /** 모든 구독자에게 하트비트(주석 줄 ': keepalive')를 보낸다. 주석 줄은 브라우저 이벤트로 올라오지 않아 화면에는 영향이 없다 */
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
        // 구독자 한 명의 전송 상태(sending·pending)는 그 구독자 객체의 잠금으로만 보호한다. 구독자끼리는 서로 막지 않는다
        synchronized (subscriber) {
            // 앞 전송이 아직 끝나지 않았다. 새 건은 종류별 대기 칸에 덮어써서 최신 하나만 남긴다(밀린 구독자에게 낡은 통계를 쌓아 보내지 않으려는 것이다)
            if (subscriber.sending) {
                if (kind == Kind.STAT) {
                    subscriber.pendingStat = event;
                } else if (kind == Kind.NOW) {
                    subscriber.pendingNow = event;
                }
                return;
            }
            // 전송 중이 아니면 이 호출이 전송 담당이 된다. 잠금 밖에서 실제 쓰기를 한다(쓰기가 막혀도 잠금을 쥐고 있지 않으려고)
            subscriber.sending = true;
        }
        try {
            // 전송은 별도 풀 스레드에서 한다. 호출한 스레드(확인 작업)는 기다리지 않는다
            sendPool.execute(() -> drain(subscriber, event));
            // 풀이 이미 닫혔으면 보낼 수 없으므로 전송 중 표시만 되돌린다
        } catch (RejectedExecutionException e) {
            // 앱을 내리는 중이다
            synchronized (subscriber) {
                subscriber.sending = false;
            }
        }
    }

    // 보내기 스레드에서: 첫 건을 보내고, 그 사이 쌓인 대기분이 있으면 이어서 보낸다
    /** 보내기 스레드가 실제로 쓴다. 첫 건을 보내고, 그동안 쌓인 대기분이 있으면 이어서 보내다가 비면 끝낸다 */
    private void drain(Subscriber subscriber, Supplier<SseEmitter.SseEventBuilder> first) {
        Supplier<SseEmitter.SseEventBuilder> next = first;
        while (next != null) {
            try {
                // 여기서 막힐 수 있다(상대가 소켓을 읽지 않을 때). 그래서 전용 풀에서 실행한다
                subscriber.emitter().send(next.get());
            } catch (IOException | IllegalStateException e) {
                // 이미 끊긴 연결이다. 목록에서 빼고 넘어간다
                // 이미 끊긴 연결이면 목록에서 빼고 대기분을 비운다
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
                // 대기분이 있으면 다음 건으로 이어서 보낸다. 통계가 현재 인원보다 우선이다
                if (subscriber.pendingStat != null) {
                    next = subscriber.pendingStat;
                    subscriber.pendingStat = null;
                } else {
                    next = subscriber.pendingNow;
                    subscriber.pendingNow = null;
                }
                // 더 보낼 것이 없으면 전송 중 표시를 내려, 다음 건이 새 전송 작업을 시작할 수 있게 한다(잠금 안에서 해야 놓치는 건이 없다)
                if (next == null) {
                    subscriber.sending = false;
                }
            }
        }
    }

    /** 구독자를 목록에서 빼고 전송 상태를 초기화한다(이후 이 구독자로 가는 건은 없다) */
    private void abandon(Subscriber subscriber) {
        subscribers.remove(subscriber);
        synchronized (subscriber) {
            subscriber.pendingStat = null;
            subscriber.pendingNow = null;
            subscriber.sending = false;
        }
    }

    // 테스트에서 하트비트를 바로 확인할 수 있게 열어 둔다
    // 하트비트 전송을 테스트에서 직접 부를 수 있게 한 통로
    public void sendHeartbeatNow() {
        sendHeartbeat();
    }

    // 구독자 한 명의 상태. regionId는 구독 지역(null이면 전체), emitter는 브라우저와 연결된 SSE 통로다
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

    // 전송 스레드에 이름을 붙이고 데몬으로 만든다(이름이 있으면 스레드 덤프에서 막힌 전송을 알아보기 쉽다)
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
