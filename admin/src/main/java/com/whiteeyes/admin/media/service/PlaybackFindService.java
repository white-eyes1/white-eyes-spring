package com.whiteeyes.admin.media.service;

import com.whiteeyes.admin.media.dto.PlaybackResponse;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingExpiredException;
import com.whiteeyes.admin.media.exception.RecordingNotFoundException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.service.ZoneCameraService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구역·시각으로 녹화와 재생 시작 위치를 찾는다(FNC-A-043). */
@Service
@RequiredArgsConstructor
public class PlaybackFindService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    private static final int RETENTION_DAYS = 30;

    private final ZoneCameraService zoneCameraService;
    private final RecordingRepository recordingRepository;

    /**
     * 구역에서 at 시각을 포함하는 녹화를 찾는다.
     *
     * @param at 찾는 시각(KST, DB와 같은 기준)
     * @param now 지금 시각(KST). 보관 기간 판정에 쓴다
     * @param basePath 응답 주소 앞에 붙일 컨텍스트 경로(관리자 앱은 /admin, 없으면 빈 문자열)
     * @throws RecordingExpiredException at이 지금보다 30일을 넘게 앞서면(404)
     * @throws RecordingNotFoundException 해당하는 녹화가 없으면(404)
     */
    @Transactional(readOnly = true)
    public PlaybackResponse findAt(Long zoneId, LocalDateTime at, LocalDateTime now, String basePath) {
        // 처리 순서 1: 보관 기간 30일을 지난 시각
        if (at.isBefore(now.minusDays(RETENTION_DAYS))) {
            throw new RecordingExpiredException();
        }
        // 처리 순서 2·3: 구역의 캠(삭제 포함) 녹화 중 at을 포함하고 started_at이 가장 늦은 것
        List<Long> cameraIds = zoneCameraService.findCameraIdsInZone(zoneId);
        if (cameraIds.isEmpty()) {
            throw new RecordingNotFoundException();
        }
        Recording recording = recordingRepository.findContaining(cameraIds, at, Limit.of(1)).stream()
                .findFirst()
                .orElseThrow(RecordingNotFoundException::new);
        // 처리 순서 4: 재생 시작 위치 = at − started_at
        double seekSec = Duration.between(recording.getStartedAt(), at).toMillis() / 1000.0;
        return toResponse(recording, seekSec, basePath);
    }

    /**
     * 구역에서 가장 최근에 시작한 녹화(30일 이내)를 찾는다. seekSec은 0이다.
     *
     * @throws RecordingNotFoundException 30일 안에 녹화가 없으면(404)
     */
    @Transactional(readOnly = true)
    public PlaybackResponse findLatest(Long zoneId, LocalDateTime now, String basePath) {
        List<Long> cameraIds = zoneCameraService.findCameraIdsInZone(zoneId);
        if (cameraIds.isEmpty()) {
            throw new RecordingNotFoundException();
        }
        Recording recording = recordingRepository
                .findFirstByCameraIdInAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
                        cameraIds, now.minusDays(RETENTION_DAYS))
                .orElseThrow(RecordingNotFoundException::new);
        return toResponse(recording, 0, basePath);
    }

    private PlaybackResponse toResponse(Recording recording, double seekSec, String basePath) {
        String recordingPath = basePath + "/api/v1/recordings/" + recording.getId();
        return new PlaybackResponse(
                recording.getId(),
                recordingPath + "/video",
                recordingPath + "/boxes",
                seekSec,
                recording.getStartedAt().atOffset(KST_OFFSET),
                toOffset(recording.getEndedAt()));
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(KST_OFFSET);
    }
}
