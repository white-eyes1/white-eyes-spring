package com.whiteeyes.admin.media.service;

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.dto.PlaybackRangesResponse;
import com.whiteeyes.admin.media.dto.PlaybackRangesResponse.Range;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.service.ZoneCameraService;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구역의 시간 범위 안에서 녹화가 있는 구간을 구한다(FNC-A-043 구간 조회, 다시보기 시간축의 '녹화 있음/없음' 띠). */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
public class PlaybackRangesService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    private static final Duration MAX_SPAN = Duration.ofHours(24);

    private final ZoneCameraService zoneCameraService;
    private final RecordingRepository recordingRepository;
    private final RetentionProperties retentionProperties;

    /**
     * @param from 범위 시작(KST, DB와 같은 기준)
     * @param to 범위 끝
     * @param now 지금 시각(KST). 진행 중 녹화의 끝과 보관 기간 판정에 쓴다
     * @throws BusinessException from ≥ to이거나 범위가 24시간을 넘으면(400 INVALID_INPUT)
     */
    @Transactional(readOnly = true)
    public PlaybackRangesResponse findRanges(Long zoneId, LocalDateTime from, LocalDateTime to, LocalDateTime now) {
        if (!from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "from은 to보다 앞이어야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "범위는 24시간 이내여야 합니다.");
        }
        // 보관 기간(RECORDING_RETENTION_DAYS)을 넘은 부분은 구간에 넣지 않는다
        LocalDateTime start = max(from, now.minusDays(retentionProperties.recordingDays()));
        if (!start.isBefore(to)) {
            return new PlaybackRangesResponse(List.of());
        }
        // 구역이 없거나 캠이 없어도 404가 아니라 빈 목록이다
        List<Long> cameraIds = zoneCameraService.findCameraIdsInZone(zoneId);
        if (cameraIds.isEmpty()) {
            return new PlaybackRangesResponse(List.of());
        }

        List<Span> spans = new ArrayList<>();
        for (Recording recording : recordingRepository.findOverlapping(cameraIds, start, to)) {
            // 진행 중(ended_at 없음)인 녹화는 지금까지로 본다
            LocalDateTime end = recording.getEndedAt() == null ? now : recording.getEndedAt();
            LocalDateTime clippedStart = max(recording.getStartedAt(), start);
            LocalDateTime clippedEnd = min(end, to);
            if (clippedStart.isBefore(clippedEnd)) {
                spans.add(new Span(clippedStart, clippedEnd));
            }
        }
        return new PlaybackRangesResponse(merge(spans));
    }

    // 캠 구분 없이 합쳐서 겹치거나 맞닿은 구간만 하나로 잇는다. 틈이 있으면 끊어서 영상이 있는 구간만 띠에 보인다(#296)
    private List<Range> merge(List<Span> spans) {
        spans.sort(Comparator.comparing(Span::start));
        List<Range> merged = new ArrayList<>();
        LocalDateTime currentStart = null;
        LocalDateTime currentEnd = null;
        for (Span span : spans) {
            if (currentStart == null) {
                currentStart = span.start();
                currentEnd = span.end();
            } else if (!span.start().isAfter(currentEnd)) {
                currentEnd = max(currentEnd, span.end());
            } else {
                merged.add(toRange(currentStart, currentEnd));
                currentStart = span.start();
                currentEnd = span.end();
            }
        }
        if (currentStart != null) {
            merged.add(toRange(currentStart, currentEnd));
        }
        return merged;
    }

    private Range toRange(LocalDateTime from, LocalDateTime to) {
        return new Range(from.atOffset(KST_OFFSET), to.atOffset(KST_OFFSET));
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private record Span(LocalDateTime start, LocalDateTime end) {}
}
