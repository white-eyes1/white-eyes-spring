/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/MinuteAccumulatorStore.java
 * 관련 이슈: #16(FNC-A-024), closeMinute를 부르는 쪽은 #18(FNC-A-028)
 * #18에서 바꾼 것: closeMinutesEndedBy(분이 끝난 바구니를 한꺼번에 마감) 추가, import 2줄(Comparator, List)
 *
 * [무엇] 모든 (구역, 분) 바구니(MinuteAccumulator)를 보관하는 메모리 저장소 + '이미 마감한 (구역, 분)' 기록.
 *   DB가 아니라 메모리에 모으는 이유: 1초마다 DB에 쓰지 않고 1분에 1번만 쓰려고(requirements.md stagg-001 "DB 쓰기를 1/60로").
 *
 * [핵심 설계 1: '이미 마감된 분'을 시각이 아니라 기록으로 판정한다] (사용자와 합의한 결정 1)
 *   명세끼리 충돌이 있었다.
 *   - FNC-A-028: "분 끝 + 5초가 지나면 마감", conventions.md: "이후 도착한 결과는 버림"
 *   - FNC-S-006 검수 기준: "관리자 앱을 2분 껐다 켜면 그 사이 1분 통계가 채워짐"
 *   시각으로만 판정하면(지금 ≥ 분 끝+5초면 버림) 앱이 다시 켜진 뒤 분석 서버가 보낸 '꺼져 있던 2분'의 결과가 전부 버려져
 *   두 번째 기준을 지킬 수 없다. 그래서 '실제로 마감한 적이 있는 (구역, 분)'만 버린다.
 *   - 앱을 재시작하면 마감 기록이 비어 있음 → 재전송분을 받아들임 → #18이 다음 확인 때 곧바로 마감·저장 → 통계가 채워짐
 *   - 정상 운영 중에는 #18이 분 끝+5초에 마감하면서 기록을 남김 → 그 뒤 늦게 온 결과는 기록에 걸려 버려짐
 *
 * [핵심 설계 2: 마감 기록은 10분만 보관]
 *   기록을 영원히 두면 메모리가 계속 늘어난다. 분석 서버는 300초(RESULT_RETRY_BUFFER_SEC)보다 오래된 결과는 스스로 버리므로
 *   그보다 넉넉한 10분만 두면 충분하다. 기록이 없으면 생길 사고: 마감 후 늦게 온 재전송분이 같은 분의 바구니를 새로 만들고,
 *   #18이 그 작은 바구니로 '덮어쓰기 저장'(ON DUPLICATE KEY UPDATE)을 해서 정상 행을 일부 데이터로 망가뜨린다.
 *
 * [핵심 설계 3: 동시성] 결과 수신(HTTP 요청 스레드)과 1분 마감(#18의 예약 작업 스레드)이 동시에 이 객체를 건드린다.
 *   - 메서드를 모두 synchronized로 묶어 '넣기'와 '마감'이 절대 겹치지 않게 했다.
 *   - ConcurrentHashMap 대신 synchronized를 고른 이유: "마감 기록 확인 → 바구니에 넣기"처럼 두 자료구조를 함께 보는
 *     동작이라, 맵 하나만 스레드 안전해서는 그 사이에 마감이 끼어드는 틈을 막을 수 없다.
 *   - 성능: 요청이 1초에 1번 정도이고 잠금 안의 일이 아주 짧아 기다림이 거의 없다(단순함을 택함).
 */
package com.whiteeyes.admin.stat.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 구역·분별 1분 집계용 메모리 누적기(FNC-A-024)와 마감 기록.
 *
 * <p>'이미 마감된 분'은 시각이 아니라 실제로 마감한 (구역, 분) 기록으로 판정한다. 관리자 앱이 재시작하면 기록이 비어 있어,
 * 분석 서버가 재전송한 꺼져 있던 동안의 결과를 받아 다시 채울 수 있다(FNC-S-006 검수 기준).
 * 결과 수신과 마감(FNC-A-028)이 다른 스레드에서 오므로 모든 접근을 이 객체의 잠금으로 묶는다.
 */
// [해석] @Component: Spring이 앱 전체에서 딱 하나(싱글턴)만 만든다. 그래서 결과 수신 서비스와 #18의 마감 작업이
//        같은 저장소(같은 메모리)를 보게 된다. 서비스(@Service)가 아니라 Component인 이유: 업무 판단 없이 '보관'만 하기 때문.
@Component
public class MinuteAccumulatorStore {

    // 분석 서버의 재전송 한도(RESULT_RETRY_BUFFER_SEC 300초)보다 길게 둬서, 마감 뒤 늦게 온 재전송분이 같은 분을 새 누적기로 다시 만들지 않게 한다
    // [해석] 매직 넘버를 상수로 뺀 것(code-conventions 2-5). 환경변수로 빼지 않은 이유: 운영 중에 바꿀 값이 아니라
    //        분석 서버의 재전송 한도에 묶인 '안전 여유'이기 때문. 재전송 한도가 늘면 이 값도 함께 늘려야 한다.
    private static final Duration CLOSED_MINUTE_RETENTION = Duration.ofMinutes(10);

    // [해석] 아직 열려 있는 바구니들. (구역, 분) → 바구니.
    private final Map<MinuteKey, MinuteAccumulator> accumulators = new HashMap<>();
    // [해석] 마감한 (구역, 분)의 기록. 여기 있으면 그 분의 결과는 버린다.
    //        일반 HashMap/HashSet을 쓴 이유: 아래 메서드들이 전부 synchronized라 이미 한 번에 한 스레드만 들어온다.
    private final Set<MinuteKey> closedMinutes = new HashSet<>();

    /**
     * 1초 결과를 그 초가 속한 분의 누적기에 넣는다. 같은 초가 이미 있으면 덮어쓴다.
     *
     * @param second KST, 초 단위로 자른 시각
     * @return 넣었으면 true, 그 분이 이미 마감돼 버렸으면 false
     */
    // [해석] true/false를 돌려주는 이유: 호출한 서비스가 '버렸는지'를 알아야 버린 건수를 세고,
    //        버린 결과로 마지막 결과 시각을 갱신하지 않을 수 있다.
    public synchronized boolean add(long zoneId, LocalDateTime second, SecondResult result) {
        // [해석] 14:03:05 → 14:03:00. 그 초가 속한 1분 구간의 시작이 바구니의 '분' 키다.
        MinuteKey key = new MinuteKey(zoneId, second.truncatedTo(ChronoUnit.MINUTES));
        // [해석] FNC-A-024 처리 순서 2 "at이 속한 1분이 이미 마감됐으면 버림(늦게 도착)".
        if (closedMinutes.contains(key)) {
            return false;
        }
        // [해석] 바구니가 없으면 새로 만들고(computeIfAbsent), 있으면 그 바구니에 넣는다. 같은 초면 덮어쓴다.
        accumulators
                .computeIfAbsent(key, minuteKey -> new MinuteAccumulator(minuteKey.zoneId(), minuteKey.minute()))
                .put(second, result);
        return true;
    }

    /**
     * 그 구역·분을 마감한다. 이후 같은 분의 결과는 {@link #add}에서 버린다.
     *
     * @return 마감한 누적기. 받은 결과가 하나도 없었으면 빈 값
     */
    // [해석] #18이 부를 메서드인데 #16에 둔 이유: "마감된 분의 결과는 버린다"는 #16의 검수 기준을 테스트하려면
    //        '마감'이라는 동작이 있어야 하기 때문. 마감 시점 판단(분 끝+5초)과 저장은 여전히 #18의 몫이다.
    //        #18은 '지금 열려 있는 바구니 목록'을 찾는 메서드도 필요할 텐데, 그건 #18이 정할 일이라 미리 만들지 않았다.
    public synchronized Optional<MinuteAccumulator> closeMinute(long zoneId, LocalDateTime minute) {
        MinuteKey key = new MinuteKey(zoneId, minute);
        // [해석] 바구니가 없어도(결과가 없던 분) 기록은 남긴다. 그래야 그 뒤에 늦게 온 결과도 버려진다.
        closedMinutes.add(key);
        // [해석] 오래된 기록 정리. '지금 시각' 대신 '방금 마감한 분'을 기준으로 10분 이전 것을 지운다.
        //        시계(Clock)를 주입하지 않아도 되고, 테스트에서 시각을 흉내 낼 필요도 없어 단순하다.
        //        #18이 매분 마감하므로 정리도 매분 자연스럽게 일어난다.
        LocalDateTime retentionStart = minute.minus(CLOSED_MINUTE_RETENTION);
        closedMinutes.removeIf(closed -> closed.minute().isBefore(retentionStart));
        // [해석] 바구니를 저장소에서 빼서 돌려준다(FNC-A-028 처리 순서 5 "누적기 삭제"와 같은 효과).
        //        빼낸 뒤에는 아무도 이 바구니에 넣지 않으므로, #18은 잠금 없이 안전하게 읽을 수 있다.
        //        결과가 없던 분이면 Optional.empty() → #18은 행을 만들지 않으면 된다(FNC-A-028 "빈 구간").
        return Optional.ofNullable(accumulators.remove(key));
    }

    /**
     * 분 끝 시각이 cutoff 이하인 누적기를 모두 마감해 꺼낸다(FNC-A-028 처리 순서 1). 분·구역 순서로 돌려준다.
     *
     * @param cutoff 지금 − MINUTE_CLOSE_GRACE_SEC. 14:03 구간은 cutoff가 14:04:00 이상일 때 마감된다
     */
    // [해석] #18에서 추가. #16의 PR 본문에 "열려 있는 누적기 목록을 찾는 메서드는 #18에서 추가해야 한다"고 적어 둔 그 메서드다.
    //        목록을 따로 꺼내 주고 #18이 하나씩 closeMinute를 부르는 방식(메서드 두 개)으로 하지 않고, 찾기와 마감을
    //        한 메서드 안에서 끝낸 이유: 두 동작 사이에 잠금이 풀리면 그 틈에 결과가 들어오거나 다른 실행이 같은 분을
    //        마감할 수 있다. synchronized 메서드 하나로 묶으면 그런 틈이 없다.
    public synchronized List<MinuteAccumulator> closeMinutesEndedBy(LocalDateTime cutoff) {
        // [해석] 분 끝 = 분 시작 + 1분. "분 끝 ≤ cutoff"를 "분 끝이 cutoff보다 뒤가 아니다(!isAfter)"로 쓴다.
        //        14:03 구간의 끝은 14:04:00이므로 cutoff가 14:04:00이면(= 지금 14:04:05) 마감, 14:03:59면 아직 열어 둔다.
        //        정렬은 결과에 영향을 주지 않지만, 저장·로그 순서가 매번 같아야 테스트와 로그를 읽기 쉽다.
        //        (HashMap은 순서가 정해져 있지 않다.)
        List<MinuteKey> endedKeys = accumulators.keySet().stream()
                .filter(key -> !key.minute().plusMinutes(1).isAfter(cutoff))
                .sorted(Comparator.comparing(MinuteKey::minute).thenComparing(MinuteKey::zoneId))
                .toList();
        // [해석] 이미 있는 closeMinute를 그대로 불러 '마감 기록 남기기 + 바구니 빼기'를 같은 방식으로 한다.
        //        endedKeys는 바구니가 있는 키만 골랐으므로 결과가 비어 있을 수 없어 orElseThrow를 쓴다.
        //        먼저 키 목록을 toList()로 확정한 뒤 마감하는 이유: 맵을 돌면서 그 맵에서 지우면 오류가 난다.
        //        synchronized 메서드 안에서 다른 synchronized 메서드를 부르는 것은 같은 스레드라 문제없다(재진입).
        return endedKeys.stream()
                .map(key -> closeMinute(key.zoneId(), key.minute()).orElseThrow())
                .toList();
    }

    // [해석] (구역, 분) 두 값을 묶은 맵의 키. record라 equals/hashCode가 두 값으로 자동 생성돼 키로 바로 쓸 수 있다.
    //        이 클래스 안에서만 쓰므로 private.
    private record MinuteKey(long zoneId, LocalDateTime minute) {}
}
