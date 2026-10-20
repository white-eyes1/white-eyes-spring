package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest.DirectionCount;

/** 분석 결과 수신(FNC-A-024) */
@Service
public class ResultReceiveService {

    private static final Logger log = LoggerFactory.getLogger(ResultReceiveService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraQueryService cameraQueryService;
    private final MinuteAccumulatorStore minuteAccumulatorStore;
    private final LastResultTimeStore lastResultTimeStore;

    public ResultReceiveService(
            CameraQueryService cameraQueryService,
            MinuteAccumulatorStore minuteAccumulatorStore,
            LastResultTimeStore lastResultTimeStore) {
        this.cameraQueryService = cameraQueryService;
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.lastResultTimeStore = lastResultTimeStore;
    }

    public void receiveResults(List<ResultReceiveRequest> results) {
        // 항목마다 조회하지 않고 묶음 안의 캠을 한 번에 조회(FNC-A-024 처리 순서 1)
        Set<Long> cameraIds =
                results.stream().map(ResultReceiveRequest::cameraId).collect(Collectors.toSet());
        Map<Long, Long> zoneIdByCamera = cameraQueryService.findZoneIdsOfReceivingCameras(cameraIds);

        int droppedCount = 0;
        for (ResultReceiveRequest result : results) {
            Long zoneId = zoneIdByCamera.get(result.cameraId());
            LocalDateTime second = toKstSecond(result.at());
            // 삭제·정지·없는 캠이거나 이미 마감된 분이면 버림(FNC-A-024 처리 순서 1·2)
            if (zoneId == null || !minuteAccumulatorStore.add(zoneId, second, toSecondResult(result))) {
                droppedCount++;
                continue;
            }
            lastResultTimeStore.update(result.cameraId(), second);
        }
        // 1초마다 오는 요청이라 DEBUG로만 남김(code-conventions 7-3)
        log.debug("분석 결과 수신 received={} dropped={}", results.size(), droppedCount);
    }

    private LocalDateTime toKstSecond(OffsetDateTime at) {
        return at.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
    }

    private SecondResult toSecondResult(ResultReceiveRequest result) {
        return new SecondResult(
                result.personCount(),
                toDirectionCounts(result.in()),
                toDirectionCounts(result.out()),
                result.dwellSec());
    }

    private DirectionCounts toDirectionCounts(DirectionCount count) {
        return new DirectionCounts(count.north(), count.east(), count.south(), count.west());
    }
}
