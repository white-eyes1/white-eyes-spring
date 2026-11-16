package com.whiteeyes.admin.media.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 다시보기 녹화 구간 조회 응답(FNC-A-043 구간 조회). 시간순이며 서로 겹치거나 맞닿은 구간만 하나로 합쳐져 있다(틈이 있으면 나뉜다).
 */
public record PlaybackRangesResponse(List<Range> ranges) {

    /** 녹화가 이어진 한 구간. 시각은 한국 시간 오프셋(+09:00)을 붙여 내려간다. */
    public record Range(OffsetDateTime from, OffsetDateTime to) {}
}
