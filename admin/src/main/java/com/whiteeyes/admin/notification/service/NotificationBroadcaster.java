package com.whiteeyes.admin.notification.service;

import com.whiteeyes.common.event.service.UncheckedEventCountService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 알림 스트림(FNC-A-048). 연결된 관리자 화면의 목록을 메모리에 들고(관리자 앱 인스턴스 1개 기준)
 * event-created / unchecked-count / camera-status 를 보낸다. 연결 직후 현재 미확인 수를 한 번 보내고,
 * 30초마다 주석 줄을 보내 nginx·브라우저가 연결을 끊지 않게 한다.
 *
 * <p>실제 전송은 구독자마다 보내기 풀에서 한다. 소켓을 읽지 않는 구독자 하나가 쓰기에서 막혀도(Tomcat 기본 60초)
 * 다른 구독자의 전송과 알림을 부른 스레드는 멈추지 않는다. 통계(StatStreamBroadcaster)와 달리 알림은 하나도
 * 건너뛰면 안 되므로 구독자마다 순서대로 쌓아 두고, 쌓인 것이 {@link #MAX_QUEUED}건을 넘으면 그 연결을 끊는다
 * (브라우저가 다시 연결하며 현재 미확인 수를 새로 받는다).
 */
@Slf4j
@Service
// 웹 서버 없이 띄우는 실행(--job=… 같은 1회 작업)에는 SSE도 하트비트도 필요 없다
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class NotificationBroadcaster {

    static final int HEARTBEAT_SECONDS = 30;
    static final long RECONNECT_MILLIS = 5000;
    static final int MAX_QUEUED = 200;

    private final JsonMapper jsonMapper;
    private final UncheckedEventCountService uncheckedEventCountService;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService heartbeatExecutor;
    // 구독자별 전송용. 막힌 구독자 수만큼만 스레드가 늘고, 놀면 60초 뒤 정리된다
    private final ExecutorService sendPool = Executors.newCachedThreadPool(new SendThreadFactory());

    public NotificationBroadcaster(JsonMapper jsonMapper, UncheckedEventCountService uncheckedEventCountService) {
        this.jsonMapper = jsonMapper;
        this.uncheckedEventCountService = uncheckedEventCountService;
    }

    @PostConstruct
    void start() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor.scheduleWithFixedDelay(
                this::safeHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        stop();
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        sendPool.shutdownNow();
    }

    // 연결을 모두 닫는다. 하트비트와 보내기 풀은 남겨 두어 테스트가 이어서 쓸 수 있다
    public void stop() {
        subscribers.forEach(subscriber -> subscriber.emitter().complete());
        subscribers.clear();
    }

    /** 구독을 시작한다. */
    public SseEmitter subscribe() {
        return subscribe(new SseEmitter(0L));
    }

    // 시험에서 일부러 막히거나 끊기는 emitter를 넣을 수 있게 열어 둔다
    public SseEmitter subscribe(SseEmitter emitter) {
        Subscriber subscriber = new Subscriber(emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> subscribers.remove(subscriber));
        // 연결이 끊기면 브라우저가 5초 뒤 다시 연결한다(retry 5000)
        enqueue(subscriber, () -> SseEmitter.event().reconnectTime(RECONNECT_MILLIS).comment("connected"));
        // 연결 직후 현재 미확인 수를 한 번 보낸다(업무 규칙)
        enqueue(subscriber, eventOf("unchecked-count", uncheckedEventCountService.count()));
        return emitter;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** 연결된 모든 구독자에게 이벤트 하나를 보낸다. payload는 JSON으로 바꿔 data에 담는다. */
    public void broadcast(String eventName, Object payload) {
        Supplier<SseEmitter.SseEventBuilder> event = eventOf(eventName, payload);
        for (Subscriber subscriber : subscribers) {
            enqueue(subscriber, event);
        }
    }

    private Supplier<SseEmitter.SseEventBuilder> eventOf(String eventName, Object payload) {
        String json = jsonMapper.writeValueAsString(payload);
        return () -> SseEmitter.event().name(eventName).data(json);
    }

    private void safeHeartbeat() {
        try {
            sendHeartbeatNow();
        } catch (RuntimeException e) {
            // 한 번 실패해도 30초 뒤 다시 보내야 하므로 작업이 죽지 않게 한다
            log.warn("알림 스트림 하트비트 중 오류가 났습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }

    // 테스트에서 하트비트를 바로 확인할 수 있게 열어 둔다
    public void sendHeartbeatNow() {
        for (Subscriber subscriber : subscribers) {
            enqueue(subscriber, () -> SseEmitter.event().comment("keepalive"));
        }
    }

    // 구독자의 전송 순서에 한 건을 넣는다. 앞 전송이 진행 중이면 대기열에 쌓는다
    private void enqueue(Subscriber subscriber, Supplier<SseEmitter.SseEventBuilder> event) {
        synchronized (subscriber) {
            if (subscriber.sending) {
                if (subscriber.queue.size() >= MAX_QUEUED) {
                    log.warn("알림 스트림 대기열이 가득 차 이 연결을 닫습니다. 브라우저가 다시 연결합니다.");
                    drop(subscriber);
                    return;
                }
                subscriber.queue.add(event);
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
                drop(subscriber);
                return;
            } catch (RuntimeException e) {
                // 한 구독자의 예상 못한 오류가 이 스레드와 다른 구독자에게 번지지 않게 한다
                log.warn("알림 전송 중 오류가 나 이 연결을 닫습니다.", e);
                drop(subscriber);
                return;
            }
            synchronized (subscriber) {
                next = subscriber.queue.poll();
                if (next == null) {
                    subscriber.sending = false;
                }
            }
        }
    }

    private void drop(Subscriber subscriber) {
        subscribers.remove(subscriber);
        synchronized (subscriber) {
            subscriber.queue.clear();
            subscriber.sending = false;
        }
    }

    private static final class Subscriber {
        private final SseEmitter emitter;
        // 아래 둘은 이 객체의 잠금 안에서만 바꾼다
        private boolean sending;
        private final Queue<Supplier<SseEmitter.SseEventBuilder>> queue = new ArrayDeque<>();

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        SseEmitter emitter() {
            return emitter;
        }
    }

    private static final class SendThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "notification-send-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
