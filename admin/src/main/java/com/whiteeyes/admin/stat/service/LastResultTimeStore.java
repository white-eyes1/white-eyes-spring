package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 캠별 마지막 결과 시각(FNC-A-024 처리 순서 4). 캠 이상 감지(FNC-A-035)가 결과가 끊겼는지 판단할 때 쓴다.
 */
@Component
public class LastResultTimeStore {

    // 캠 번호 → 마지막으로 받은 결과의 시각. 결과 수신 스레드와 이상 감지 스레드가 함께 접근하므로 동시성 맵을 쓴다
    private final Map<Long, LocalDateTime> lastResultAtByCamera = new ConcurrentHashMap<>();

    // 감시 시작 시각으로만 채웠고 실제 결과는 아직 받지 못한 캠
    private final Set<Long> seededOnlyCameras = ConcurrentHashMap.newKeySet();

    /** 더 늦은 시각일 때만 바꾼다. 재전송된 옛 결과가 마지막 시각을 되돌리지 않게 한다. */
    // merge는 키가 없으면 그대로 넣고, 있으면 아래 병합 함수로 둘 중 늦은 시각을 고른다(원자적으로 처리)
    public void update(long cameraId, LocalDateTime resultAt) {
        lastResultAtByCamera.merge(
                cameraId, resultAt, (current, received) -> received.isAfter(current) ? received : current);
        // 실제 결과를 받았으므로 더 이상 '감시 시작 시각으로만 채운 캠'이 아니다
        seededOnlyCameras.remove(cameraId);
    }

    /**
     * 기준 시각이 아직 없을 때만 넣는다. 결과를 한 번도 못 받은 캠(앱 재시작 직후 등)이 바로 '결과 끊김'으로
     * 판정되지 않게 감시 시작 시각을 기준으로 삼는다(FNC-A-035). 실제 결과가 들어오기 전까지는
     * {@link #isSeededOnly}가 true다.
     */
    public void seedIfAbsent(long cameraId, LocalDateTime at) {
        // 이미 값이 있으면(실제 결과든 이전 기준이든) 덮어쓰지 않는다. 새로 넣었을 때만 '기준 시각만 있음' 표시를 한다
        if (lastResultAtByCamera.putIfAbsent(cameraId, at) == null) {
            seededOnlyCameras.add(cameraId);
        }
    }

    /** 기준 시각이 감시 시작 시각일 뿐 실제 결과를 아직 한 번도 받지 못한 캠이면 true. */
    public boolean isSeededOnly(long cameraId) {
        return seededOnlyCameras.contains(cameraId);
    }

    /** 캠의 마지막 결과 시각(기준 시각 포함). 한 번도 기록된 적이 없으면 빈 값이다. */
    public Optional<LocalDateTime> findLastResultAt(long cameraId) {
        return Optional.ofNullable(lastResultAtByCamera.get(cameraId));
    }
}
