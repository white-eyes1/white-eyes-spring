package com.whiteeyes.admin.notification.service;

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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.event.service.UncheckedEventCountService;

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
@RequiredArgsConstructor
public class NotificationBroadcaster {

    // 하트비트(연결 유지용 주석 줄) 간격(초). 아무 데이터도 없을 때 중간 장비가 연결을 끊지 않게 한다.
    static final int HEARTBEAT_SECONDS = 30;
    // 연결이 끊긴 뒤 브라우저가 다시 연결을 시도할 때까지 기다릴 시간(ms). SSE의 retry 값으로 내려간다.
    static final long RECONNECT_MILLIS = 5000;
    // 구독자 한 명에게 쌓아 둘 수 있는 보내기 대기 건수 상한. 넘으면 소켓을 읽지 않는 연결로 보고 끊는다.
    static final int MAX_QUEUED = 200;

    // 알림 객체를 JSON 문자열로 바꾼다.
    private final JsonMapper jsonMapper;
    // 연결 직후 보낼 현재 미확인 이벤트 수를 구한다.
    private final UncheckedEventCountService uncheckedEventCountService;

    // 연결된 구독자. 순회(전송)가 잦고 추가·삭제는 드물어 CopyOnWriteArrayList를 쓴다(순회 중 삭제돼도 안전).
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    // 하트비트 전용 스레드 하나. 데몬 스레드라 앱 종료를 막지 않는다.
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notification-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    // 구독자별 전송용. 막힌 구독자 수만큼만 스레드가 늘고, 놀면 60초 뒤 정리된다
    // (위 주석 참고) 구독자별 전송은 이 풀에서 하므로, 한 연결이 막혀도 호출한 스레드와 다른 연결은 멈추지 않는다.
    private final ExecutorService sendPool = Executors.newCachedThreadPool(new SendThreadFactory());

    // 빈 생성 직후 하트비트를 예약한다. 앞 작업이 끝난 뒤 간격을 두고 다시 실행(scheduleWithFixedDelay)해 겹쳐 실행되지 않는다.
    @PostConstruct
    void start() {
        heartbeatExecutor.scheduleWithFixedDelay(
                this::safeHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    // 앱 종료 시 모든 연결과 스레드를 정리한다.
    @PreDestroy
    void shutdown() {
        stop();
        heartbeatExecutor.shutdownNow();
        sendPool.shutdownNow();
    }

    // 연결을 모두 닫는다. 하트비트와 보내기 풀은 남겨 두어 테스트가 이어서 쓸 수 있다
    public void stop() {
        subscribers.forEach(subscriber -> subscriber.emitter().complete());
        subscribers.clear();
    }

    /** 구독을 시작한다. */
    // 타임아웃 0은 서버가 먼저 끊지 않고 계속 연결을 유지한다는 뜻이다(끊김은 하트비트 실패 등으로 감지).
    public SseEmitter subscribe() {
        return subscribe(new SseEmitter(0L));
    }

    // 시험에서 일부러 막히거나 끊기는 emitter를 넣을 수 있게 열어 둔다
    /** 주어진 emitter를 구독자로 등록한다. 연결이 끝나거나 오류가 나면 목록에서 빼도록 콜백을 건다. */
    public SseEmitter subscribe(SseEmitter emitter) {
        Subscriber subscriber = new Subscriber(emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        // 시간 초과도 정상 종료로 처리해 완료 콜백(구독자 제거)이 돌게 한다.
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> subscribers.remove(subscriber));
        // 연결이 끊기면 브라우저가 5초 뒤 다시 연결한다(retry 5000)
        enqueue(
                subscriber,
                () -> SseEmitter.event().reconnectTime(RECONNECT_MILLIS).comment("connected"));
        // 연결 직후 현재 미확인 수를 한 번 보낸다(업무 규칙)
        // 화면이 처음 그려질 때 필요한 기준 값을 보내, 이후 변화는 이벤트로만 받으면 되게 한다.
        enqueue(subscriber, eventOf("unchecked-count", uncheckedEventCountService.count()));
        return emitter;
    }

    // 현재 연결된 구독자 수(시험·점검용).
    public int subscriberCount() {
        return subscribers.size();
    }

    /** 연결된 모든 구독자에게 이벤트 하나를 보낸다. payload는 JSON으로 바꿔 data에 담는다. */
    public void broadcast(String eventName, Object payload) {
        // JSON 변환은 한 번만 하고, 모든 구독자가 같은 전송 내용을 쓴다.
        Supplier<SseEmitter.SseEventBuilder> event = eventOf(eventName, payload);
        for (Subscriber subscriber : subscribers) {
            enqueue(subscriber, event);
        }
    }

    /** 이벤트 이름과 payload로 SSE 전송 내용을 만든다. 전송 시점에 빌더를 만들도록 Supplier로 감싼다(큐에 쌓아 두었다가 쓰기 위함). */
    private Supplier<SseEmitter.SseEventBuilder> eventOf(String eventName, Object payload) {
        String json = jsonMapper.writeValueAsString(payload);
        return () -> SseEmitter.event().name(eventName).data(json);
    }

    /** 하트비트를 보내되, 예외가 예약 작업 밖으로 나가면 이후 실행이 취소되므로 여기서 삼킨다. */
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
            // 데이터 이벤트가 아닌 주석 줄(SSE comment)이라 화면 쪽에서는 이벤트로 처리되지 않는다.
            enqueue(subscriber, () -> SseEmitter.event().comment("keepalive"));
        }
    }

    // 구독자의 전송 순서에 한 건을 넣는다. 앞 전송이 진행 중이면 대기열에 쌓는다
    /**
     * 구독자 한 명에게 보낼 이벤트를 순서대로 넣는다. 앞 전송이 진행 중이면 대기열에 쌓고,
     * 진행 중이 아니면 보내기 스레드를 시작시킨다. 구독자마다 한 번에 하나의 전송만 돌아 순서가 보장된다.
     */
    private void enqueue(Subscriber subscriber, Supplier<SseEmitter.SseEventBuilder> event) {
        // sending 플래그와 대기열은 이 객체의 잠금으로만 바꾼다(전송 스레드와 호출 스레드가 함께 건드린다).
        synchronized (subscriber) {
            if (subscriber.sending) {
                // 알림은 건너뛰면 안 되므로 버리지 않고, 대기열이 한도를 넘는 느린 연결은 끊는다.
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
            // 락 밖에서 보낸다: 느린 소켓 쓰기가 락을 오래 쥐지 않게 한다.
            sendPool.execute(() -> drain(subscriber, event));
        } catch (RejectedExecutionException e) {
            // 앱을 내리는 중이다
            synchronized (subscriber) {
                subscriber.sending = false;
            }
        }
    }

    // 보내기 스레드에서: 첫 건을 보내고, 그 사이 쌓인 대기분이 있으면 이어서 보낸다
    /** 보내기 스레드에서 실행된다. 받은 한 건을 보낸 뒤 대기열이 빌 때까지 이어서 보내고, 비면 sending을 풀고 끝낸다. */
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
                // 대기열 확인과 sending 해제를 같은 락 안에서 해야, 그 사이 새로 들어온 건이 누락되지 않는다.
                next = subscriber.queue.poll();
                if (next == null) {
                    subscriber.sending = false;
                }
            }
        }
    }

    /** 구독자를 목록에서 빼고 쌓인 대기분을 버린다(연결이 끊겼거나 감당 못 하는 경우). */
    private void drop(Subscriber subscriber) {
        subscribers.remove(subscriber);
        synchronized (subscriber) {
            subscriber.queue.clear();
            subscriber.sending = false;
        }
    }

    /** 연결 하나의 상태: emitter, 전송 중 여부, 보낼 대기열. */
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

    /** 보내기 풀 스레드에 notification-send-N 이름을 붙이고 데몬으로 만든다. */
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
