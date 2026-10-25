package com.whiteeyes.common.stat.controller;

import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.HeatmapRequest;
import com.whiteeyes.common.stat.dto.HeatmapResponse;
import com.whiteeyes.common.stat.service.HeatmapService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/map/heatmap
/** 시간대별 히트맵(FNC-C-011) */
@RestController
public class HeatmapApiController {

    private final HeatmapService heatmapService;

    public HeatmapApiController(HeatmapService heatmapService) {
        this.heatmapService = heatmapService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/map/heatmap")
    public HeatmapResponse getHeatmap(
            @RequestParam("regionId") long regionId,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam("item") String item,
            @RequestParam(name = "dayType", required = false) String dayType)
            throws BindException {
        return heatmapService.findHeatmap(HeatmapRequest.parse(regionId, from, to, item, dayType));
    }
}
