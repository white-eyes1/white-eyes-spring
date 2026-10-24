package com.whiteeyes.common.stat.controller;

import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.StatSeriesRequest;
import com.whiteeyes.common.stat.dto.StatSeriesResponse;
import com.whiteeyes.common.stat.dto.StatTargetType;
import com.whiteeyes.common.stat.service.StatSeriesService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/stats/series
/** 통계 그래프 조회(FNC-C-012) */
@RestController
public class StatSeriesApiController {

    private final StatSeriesService statSeriesService;

    public StatSeriesApiController(StatSeriesService statSeriesService) {
        this.statSeriesService = statSeriesService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/stats/series")
    public StatSeriesResponse getSeries(
            @RequestParam("targetType") StatTargetType targetType,
            @RequestParam("targetId") long targetId,
            @RequestParam("interval") String interval,
            @RequestParam("from") String from,
            @RequestParam("to") String to)
            throws BindException {
        return statSeriesService.findSeries(StatSeriesRequest.parse(targetType, targetId, interval, from, to));
    }
}
