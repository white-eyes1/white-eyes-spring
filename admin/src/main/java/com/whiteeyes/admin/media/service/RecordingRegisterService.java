package com.whiteeyes.admin.media.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.entity.CameraSourceType;

@Service
public class RecordingRegisterService {

    private static final Logger log = LoggerFactory.getLogger(RecordingRegisterService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraQueryService cameraQueryService;
    private final RecordingRepository recordingRepository;
    private final RecordingPathValidator recordingPathValidator;

    public RecordingRegisterService(
            CameraQueryService cameraQueryService,
            RecordingRepository recordingRepository,
            RecordingPathValidator recordingPathValidator) {
        this.cameraQueryService = cameraQueryService;
        this.recordingRepository = recordingRepository;
        this.recordingPathValidator = recordingPathValidator;
    }

    @Transactional
    public RecordingRegisterResponse registerRecording(RecordingRegisterRequest request) {
        CameraSourceType sourceType = cameraQueryService
                .findSourceTypeOfExistingCamera(request.cameraId())
                .orElseThrow(() -> new CameraNotFoundException(request.cameraId()));
        LocalDateTime startedAt = toKstMillis(request.startedAt());
        LocalDateTime endedAt = request.endedAt() == null ? null : toKstMillis(request.endedAt());
        validateEndedAt(sourceType, startedAt, endedAt);
        recordingPathValidator.validate(sourceType, request.videoPath(), request.boxPath());

        Recording recording = recordingRepository.save(Recording.register(
                request.cameraId(), startedAt, endedAt, request.videoPath(), request.boxPath()));
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
    private LocalDateTime toKstMillis(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }
}
