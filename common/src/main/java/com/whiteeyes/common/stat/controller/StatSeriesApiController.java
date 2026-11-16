package com.whiteeyes.common.stat.controller;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class StatSeriesApiController {

    // 시계열 계산은 서비스가 맡는다
    private final StatSeriesService statSeriesService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /** 구역 또는 지역의 통계(인원·밀집도·유입·유출 등)를 단위(interval)별 시계열로 돌려준다 */
    @GetMapping("/api/v1/stats/series")
    public StatSeriesResponse getSeries(
            @RequestParam("targetType") StatTargetType targetType,
            @RequestParam("targetId") long targetId,
            @RequestParam("interval") String interval,
            @RequestParam("from") String from,
            @RequestParam("to") String to)
            throws BindException {
        // 단위·시각은 문자열로 받아 StatSeriesRequest.parse가 읽고 검사한 뒤 서비스에 넘긴다
        return statSeriesService.findSeries(StatSeriesRequest.parse(targetType, targetId, interval, from, to));
    }
}
