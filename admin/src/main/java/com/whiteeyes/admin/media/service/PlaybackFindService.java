package com.whiteeyes.admin.media.service;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.dto.PlaybackResponse;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.NextRecordingNotFoundException;
import com.whiteeyes.admin.media.exception.RecordingExpiredException;
import com.whiteeyes.admin.media.exception.RecordingNotFoundException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.service.ZoneCameraService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구역·시각으로 녹화와 재생 시작 위치를 찾는다(FNC-A-043). */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
public class PlaybackFindService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // 이어 재생(FNC-A-045): 다음 녹화가 현재 녹화 끝보다 이만큼까지 앞서 시작해도 이어지는 것으로 보고, 이 시간보다 많이 비면 끝낸다
    private static final Duration CONTINUE_OVERLAP = Duration.ofSeconds(2);
    private static final Duration CONTINUE_MAX_GAP = Duration.ofSeconds(10);

    private final ZoneCameraService zoneCameraService;
    private final RecordingRepository recordingRepository;
    private final RetentionProperties retentionProperties;

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
        // 처리 순서 1: 보관 기간(RECORDING_RETENTION_DAYS)을 지난 시각
        if (at.isBefore(now.minusDays(retentionProperties.recordingDays()))) {
            throw new RecordingExpiredException();
        }
        // 처리 순서 2·3: 구역의 캠(삭제 포함) 녹화 중 at을 포함하고 started_at이 가장 늦은 것
        List<Long> cameraIds = zoneCameraService.findCameraIdsInZone(zoneId);
        if (cameraIds.isEmpty()) {
            throw new RecordingNotFoundException();
        }
        Optional<Recording> containing =
                recordingRepository.findContaining(cameraIds, at, Limit.of(1)).stream().findFirst();
        if (containing.isPresent()) {
            // 처리 순서 4: 재생 시작 위치 = at − started_at
            double seekSec = Duration.between(containing.get().getStartedAt(), at).toMillis() / 1000.0;
            return toResponse(containing.get(), seekSec, basePath);
        }
        // 분 시작 시각(이벤트 시작·1분 통계 점)은 그 1분 전체를 뜻한다. 그 1분 안에서 녹화가 시작했으면(캠 가동 직후)
        // 가장 이른 녹화의 처음으로 보낸다(#260). 분 중간 시각은 이 규칙을 쓰지 않아 녹화 없는 구간은 '영상 없음'이다
        if (at.getSecond() == 0 && at.getNano() == 0) {
            Recording startedInMinute = recordingRepository
                    .findStartedBetween(cameraIds, at, at.plusMinutes(1), Limit.of(1))
                    .stream()
                    .findFirst()
                    .orElseThrow(RecordingNotFoundException::new);
            return toResponse(startedInMinute, 0, basePath);
        }
        throw new RecordingNotFoundException();
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
                        cameraIds, now.minusDays(retentionProperties.recordingDays()))
                .orElseThrow(RecordingNotFoundException::new);
        return toResponse(recording, 0, basePath);
    }

    /**
     * 같은 캠에서 이 녹화에 이어지는 다음 녹화를 찾는다(FNC-A-045). seekSec은 0이다.
     *
     * <p>started_at ≥ 현재 녹화 ended_at − 2초 인 가장 이른 녹화이고, 그 시작이 현재 녹화 끝보다 10초를 넘게 뒤면
     * '이후 영상 없음'으로 끝낸다. 끝나지 않은(ended_at 없음) 녹화에는 이어질 녹화가 없다.
     *
     * @throws RecordingNotFoundException 녹화가 없으면(404 '영상 없음')
     * @throws NextRecordingNotFoundException 이어질 녹화가 없으면(404 '이후 영상 없음')
     */
    @Transactional(readOnly = true)
    public PlaybackResponse findNext(Long recordingId, String basePath) {
        Recording current = recordingRepository.findById(recordingId).orElseThrow(RecordingNotFoundException::new);
        if (current.getEndedAt() == null) {
            throw new NextRecordingNotFoundException();
        }
        Recording next = recordingRepository
                .findFollowing(
                        current.getCameraId(),
                        current.getId(),
                        current.getEndedAt().minus(CONTINUE_OVERLAP),
                        Limit.of(1))
                .stream()
                .findFirst()
                .filter(candidate -> !candidate.getStartedAt().isAfter(current.getEndedAt().plus(CONTINUE_MAX_GAP)))
                .orElseThrow(NextRecordingNotFoundException::new);
        return toResponse(next, 0, basePath);
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
