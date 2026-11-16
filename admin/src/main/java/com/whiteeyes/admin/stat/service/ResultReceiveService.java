package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest.DirectionCount;

/** 분석 결과 수신(FNC-A-024) */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultReceiveService {

    // 분·시간·날짜 구간을 모두 한국 시간 기준으로 나누므로, 받은 시각을 KST로 바꾸는 데 쓴다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 캠 번호 → 구역 번호 확인(받는 상태의 캠만 대상)
    private final CameraQueryService cameraQueryService;
    // 구역·분별 1분 누적기. 받은 1초 결과를 여기에 쌓는다
    private final MinuteAccumulatorStore minuteAccumulatorStore;
    // 캠별 마지막 결과 시각(캠 이상 감지가 읽는다)
    private final LastResultTimeStore lastResultTimeStore;

    /**
     * 분석 서버가 보낸 1초 결과 묶음을 1분 누적기에 쌓고 캠별 마지막 결과 시각을 갱신한다.
     * 쓸 수 없는 결과는 오류 없이 버리고 건수만 로그로 남긴다(분석 서버가 같은 묶음을 다시 보내도 문제가 없도록).
     */
    public void receiveResults(List<ResultReceiveRequest> results) {
        // 항목마다 조회하지 않고 묶음 안의 캠을 한 번에 조회(FNC-A-024 처리 순서 1)
        Set<Long> cameraIds =
                results.stream().map(ResultReceiveRequest::cameraId).collect(Collectors.toSet());
        Map<Long, Long> zoneIdByCamera = cameraQueryService.findZoneIdsOfReceivingCameras(cameraIds);

        // 버린 결과 수(로그용)
        int droppedCount = 0;
        for (ResultReceiveRequest result : results) {
            // 없는·삭제된·정지된 캠이면 맵에 없어 null이다
            Long zoneId = zoneIdByCamera.get(result.cameraId());
            // 받은 시각을 KST 초 단위로 맞춘다. 같은 초면 같은 키가 되어 재전송이 덮어쓰기로 처리된다
            LocalDateTime second = toKstSecond(result.at());
            // 삭제·정지·없는 캠이거나 이미 마감된 분이면 버림(FNC-A-024 처리 순서 1·2)
            if (zoneId == null || !minuteAccumulatorStore.add(zoneId, second, toSecondResult(result))) {
                droppedCount++;
                continue;
            }
            // 누적기에 실제로 넣은 결과만 마지막 시각으로 인정한다
            lastResultTimeStore.update(result.cameraId(), second);
        }
        // 1초마다 오는 요청이라 DEBUG로만 남김(code-conventions 7-3)
        log.debug("분석 결과 수신 received={} dropped={}", results.size(), droppedCount);
    }

    /** 오프셋이 있는 시각을 같은 순간의 KST 시각으로 바꾸고, 초 미만(밀리초 등)은 버린다. */
    private LocalDateTime toKstSecond(OffsetDateTime at) {
        return at.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 요청 DTO를 누적기가 쓰는 1초 결과로 바꾼다(웹 계층 타입이 service 안쪽으로 퍼지지 않게). */
    private SecondResult toSecondResult(ResultReceiveRequest result) {
        return new SecondResult(
                result.personCount(),
                toDirectionCounts(result.in()),
                toDirectionCounts(result.out()),
                result.dwellSec());
    }

    /** 요청의 방향별 인원(JSON 이름 N·E·S·W)을 service용 값으로 옮긴다. */
    private DirectionCounts toDirectionCounts(DirectionCount count) {
        return new DirectionCounts(count.north(), count.east(), count.south(), count.west());
    }
}
