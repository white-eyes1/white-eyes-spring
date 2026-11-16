package com.whiteeyes.admin.media.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.entity.CameraSourceType;

/** 녹화 등록 수신(FNC-A-026) */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingRegisterService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 캠의 입력 방식(라이브/더미)을 알아내기 위해 사용한다.
    private final CameraQueryService cameraQueryService;
    private final RecordingRepository recordingRepository;
    private final RecordingPathValidator recordingPathValidator;

    // 캠 여러 대의 등록이 겹치면 REPEATABLE READ의 갭 락이 (camera_id, started_at) 인덱스의 캠 경계에서 얽혀
    // 교착(1213)이 난다. READ COMMITTED는 갭 락을 걸지 않아 맞는 행만 잠그므로 교착이 없다(#301)
    /**
     * 분석 서버가 보낸 녹화를 검증하고 저장한다(FNC-A-026).
     * 더미 캠은 반복(루프)마다 종료 시각 없이 등록되므로, 새 녹화를 저장하면서 같은 캠의 이전 열린 녹화를 닫는다.
     *
     * @param request 캠 ID·시작/종료 시각·파일 경로
     * @return 저장된 녹화의 ID
     * @throws CameraNotFoundException 없거나 삭제된 캠
     * @throws RecordingRegisterException 종료 시각 또는 경로 규칙 위반
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecordingRegisterResponse registerRecording(RecordingRegisterRequest request) {
        // 입력 방식에 따라 종료 시각·경로 규칙이 다르므로 먼저 캠을 확인한다. 정지한 캠도 허용한다(라이브는 정지 뒤 마지막 녹화를 등록하기 때문).
        CameraSourceType sourceType = cameraQueryService
                .findSourceTypeOfExistingCamera(request.cameraId())
                .orElseThrow(() -> new CameraNotFoundException(request.cameraId()));
        // 요청의 오프셋 있는 시각을 DB 기준(KST, 밀리초)으로 바꾼다.
        LocalDateTime startedAt = toKstMillis(request.startedAt());
        LocalDateTime endedAt = request.endedAt() == null ? null : toKstMillis(request.endedAt());
        // 시각 규칙과 경로 규칙을 저장 전에 모두 확인한다.
        validateEndedAt(sourceType, startedAt, endedAt);
        recordingPathValidator.validate(sourceType, request.videoPath(), request.boxPath());

        Recording recording = recordingRepository.save(
                Recording.register(request.cameraId(), startedAt, endedAt, request.videoPath(), request.boxPath()));
        // 더미 캠은 반복마다 endedAt 없이 등록되므로 이번 시작 시각으로 이전 반복을 닫는다(FNC-A-026 처리 순서 2)
        int closedCount = sourceType == CameraSourceType.DUMMY
                ? recordingRepository.closeOpenRecordingsStartedBefore(request.cameraId(), startedAt)
                : 0;
        // 캠마다 1분(라이브)·반복(더미)마다 오는 요청이라 DEBUG로만 남김(code-conventions 7-3)
        log.debug(
                "녹화 등록 recordingId={} cameraId={} sourceType={} closed={}",
                recording.getId(),
                request.cameraId(),
                sourceType,
                closedCount);
        return new RecordingRegisterResponse(recording.getId());
    }

    // 더미는 endedAt 없음, 라이브는 endedAt 필수(api/analyzer.md, FNC-A-026 업무 규칙)
    /** 입력 방식별 종료 시각 규칙을 확인한다. 더미는 없어야 하고, 라이브는 있어야 하며, 있다면 시작보다 늦어야 한다. */
    private void validateEndedAt(CameraSourceType sourceType, LocalDateTime startedAt, LocalDateTime endedAt) {
        if (sourceType == CameraSourceType.DUMMY && endedAt != null) {
            throw new RecordingRegisterException("더미 캠 녹화는 endedAt 없이 등록해야 합니다.");
        }
        if (sourceType == CameraSourceType.LIVE && endedAt == null) {
            throw new RecordingRegisterException("라이브 캠 녹화는 endedAt이 필요합니다.");
        }
        if (endedAt != null && !endedAt.isAfter(startedAt)) {
            throw new RecordingRegisterException("endedAt은 startedAt보다 늦어야 합니다.");
        }
    }

    // DB에는 KST로 저장하고 started_at·ended_at은 밀리초까지 둔다(tables.md DATETIME(3))
    /** 오프셋 시각을 같은 순간의 KST 시각으로 바꾸고 밀리초 아래는 버린다. */
    private LocalDateTime toKstMillis(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }
}
