/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/LastResultTimeStore.java
 * 관련 이슈: #16(FNC-A-024 처리 순서 4), 이 값을 읽는 쪽은 FNC-A-035(캠 이상 감지, 이슈 #39)
 *
 * [무엇] 캠마다 '마지막으로 결과가 들어온 시각'을 메모리에 들고 있는 저장소.
 * [왜 필요한가] FNC-A-035는 "RUNNING인데 마지막 결과가 60초 이상 전이면 캠 이상 이벤트"를 연다. 그 판단 재료를 #16이 만든다.
 * [염두에 둔 것]
 *   - '마지막 결과 시각'을 '받은 시각(벽시계)'이 아니라 결과의 at으로 정했다. 받은 항목 중 가장 늦은 at만 남긴다(검토 필요 해석).
 *   - 관리자 앱이 시작될 때 모든 캠의 값을 시작 시각으로 초기화하는 일(FNC-A-035 업무 규칙)은 FNC-A-035의 몫이라 넣지 않았다.
 */
package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 캠별 마지막 결과 시각(FNC-A-024 처리 순서 4). 캠 이상 감지(FNC-A-035)가 결과가 끊겼는지 판단할 때 쓴다.
 */
@Component
public class LastResultTimeStore {

    // [해석] 여기서는 synchronized 대신 ConcurrentHashMap을 썼다. MinuteAccumulatorStore와 달리 자료구조가 하나뿐이고,
    //        아래 merge가 '읽고 비교하고 쓰기'를 원자적으로(중간에 끼어들 틈 없이) 해 주므로 맵 하나로 충분하다.
    private final Map<Long, LocalDateTime> lastResultAtByCamera = new ConcurrentHashMap<>();

    /** 더 늦은 시각일 때만 바꾼다. 재전송된 옛 결과가 마지막 시각을 되돌리지 않게 한다. */
    // [해석] 예: 14:03:07 결과를 받은 뒤 재전송된 14:03:05가 오면 그대로 14:03:07을 유지한다.
    //        그냥 put으로 덮어쓰면 시각이 과거로 돌아가 FNC-A-035가 '결과가 끊겼다'고 잘못 판단할 수 있다.
    //        merge(키, 새 값, 합치는 규칙): 값이 없으면 새 값을 넣고, 있으면 규칙(더 늦은 쪽)으로 고른다.
    public void update(long cameraId, LocalDateTime resultAt) {
        lastResultAtByCamera.merge(
                cameraId, resultAt, (current, received) -> received.isAfter(current) ? received : current);
    }

    // [해석] 한 번도 결과가 없던 캠은 값이 없으므로 Optional로 '없음'을 표현한다(반환값에만 Optional, code-conventions 3-3).
    public Optional<LocalDateTime> findLastResultAt(long cameraId) {
        return Optional.ofNullable(lastResultAtByCamera.get(cameraId));
    }
}
