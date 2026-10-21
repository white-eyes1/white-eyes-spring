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

    private final Map<Long, LocalDateTime> lastResultAtByCamera = new ConcurrentHashMap<>();

    /** 더 늦은 시각일 때만 바꾼다. 재전송된 옛 결과가 마지막 시각을 되돌리지 않게 한다. */
    public void update(long cameraId, LocalDateTime resultAt) {
        lastResultAtByCamera.merge(
                cameraId, resultAt, (current, received) -> received.isAfter(current) ? received : current);
    }

    public Optional<LocalDateTime> findLastResultAt(long cameraId) {
        return Optional.ofNullable(lastResultAtByCamera.get(cameraId));
    }
}
