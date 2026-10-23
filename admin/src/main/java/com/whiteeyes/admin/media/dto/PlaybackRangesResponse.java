package com.whiteeyes.admin.media.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 다시보기 녹화 구간 조회 응답(FNC-A-043 구간 조회). 시간순이며 서로 겹치거나 2초 이하로 떨어진 구간은 하나로 합쳐져 있다.
 */
public record PlaybackRangesResponse(List<Range> ranges) {

    public record Range(OffsetDateTime from, OffsetDateTime to) {}
}
