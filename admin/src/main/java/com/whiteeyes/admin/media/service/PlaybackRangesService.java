package com.whiteeyes.admin.media.service;

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

import com.whiteeyes.admin.cleanup.config.RetentionProperties;
import com.whiteeyes.admin.media.dto.PlaybackRangesResponse;
import com.whiteeyes.admin.media.dto.PlaybackRangesResponse.Range;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.service.ZoneCameraService;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 구역의 시간 범위 안에서 녹화가 있는 구간을 구한다(FNC-A-043 구간 조회, 다시보기 시간축의 '녹화 있음/없음' 띠). */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
@RequiredArgsConstructor
public class PlaybackRangesService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    // 한 번에 조회할 수 있는 최대 범위. 지나치게 넓은 범위로 대량 조회하는 것을 막는다.
    private static final Duration MAX_SPAN = Duration.ofHours(24);

    private final ZoneCameraService zoneCameraService;
    private final RecordingRepository recordingRepository;
    private final RetentionProperties retentionProperties;

    /**
     * 범위와 겹치는 녹화를 모아 범위 안으로 잘라 낸 뒤, 겹치거나 맞닿은 것끼리 합쳐 '녹화가 있는 구간' 목록을 만든다.
     *
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
        // 보관 기간 밖의 앞부분은 이미 지워질 영상이므로 범위 시작을 보관 기간 시작으로 당긴다.
        LocalDateTime start = max(from, now.minusDays(retentionProperties.recordingDays()));
        if (!start.isBefore(to)) {
            return new PlaybackRangesResponse(List.of());
        }
        // 구역이 없거나 캠이 없어도 404가 아니라 빈 목록이다
        List<Long> cameraIds = zoneCameraService.findCameraIdsInZone(zoneId);
        if (cameraIds.isEmpty()) {
            return new PlaybackRangesResponse(List.of());
        }

        // 캠별 녹화를 범위 안으로 잘라 낸 구간들(합치기 전).
        List<Span> spans = new ArrayList<>();
        for (Recording recording : recordingRepository.findOverlapping(cameraIds, start, to)) {
            // 진행 중(ended_at 없음)인 녹화는 지금까지로 본다
            // (위 주석) 끝나지 않은 녹화는 지금까지 이어졌다고 본다.
            LocalDateTime end = recording.getEndedAt() == null ? now : recording.getEndedAt();
            // 조회 범위를 넘는 부분은 잘라 낸다.
            LocalDateTime clippedStart = max(recording.getStartedAt(), start);
            LocalDateTime clippedEnd = min(end, to);
            if (clippedStart.isBefore(clippedEnd)) {
                spans.add(new Span(clippedStart, clippedEnd));
            }
        }
        return new PlaybackRangesResponse(merge(spans));
    }

    // 캠 구분 없이 합쳐서 겹치거나 맞닿은 구간만 하나로 잇는다. 틈이 있으면 끊어서 영상이 있는 구간만 띠에 보인다(#296)
    /**
     * 구간들을 시작 시각 순으로 정렬한 뒤, 앞 구간의 끝과 겹치거나 맞닿는 구간은 하나로 잇고 틈이 있으면 새 구간을 시작한다.
     */
    private List<Range> merge(List<Span> spans) {
        // 정렬해 두어야 한 번 훑는 것만으로 합칠 수 있다.
        spans.sort(Comparator.comparing(Span::start));
        List<Range> merged = new ArrayList<>();
        // 지금 합치는 중인 구간의 시작·끝. 아직 시작 전이면 null.
        LocalDateTime currentStart = null;
        LocalDateTime currentEnd = null;
        for (Span span : spans) {
            if (currentStart == null) {
                currentStart = span.start();
                currentEnd = span.end();
                // 다음 구간이 현재 구간 끝 이전(또는 같은 시각)에 시작하면 이어진 것이므로 끝만 늘린다.
            } else if (!span.start().isAfter(currentEnd)) {
                currentEnd = max(currentEnd, span.end());
            } else {
                // 틈이 있으면 지금까지 합친 구간을 확정하고 새 구간을 시작한다.
                merged.add(toRange(currentStart, currentEnd));
                currentStart = span.start();
                currentEnd = span.end();
            }
        }
        // 마지막으로 합치던 구간을 확정한다.
        if (currentStart != null) {
            merged.add(toRange(currentStart, currentEnd));
        }
        return merged;
    }

    /** 내부 구간을 응답용(+09:00 오프셋)으로 바꾼다. */
    private Range toRange(LocalDateTime from, LocalDateTime to) {
        return new Range(from.atOffset(KST_OFFSET), to.atOffset(KST_OFFSET));
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    // 합치기 전 구간을 담는 내부용 값.
    private record Span(LocalDateTime start, LocalDateTime end) {}
}
