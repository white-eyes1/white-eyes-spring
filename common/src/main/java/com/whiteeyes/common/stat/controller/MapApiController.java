package com.whiteeyes.common.stat.controller;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.MapStatusResponse;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.service.MapStatusService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/map/status
@RestController
public class MapApiController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MapStatusService mapStatusService;

    public MapApiController(MapStatusService mapStatusService) {
        this.mapStatusService = mapStatusService;
    }

    // 혼잡 단계 현황(FNC-C-009). 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/map/status")
    public MapStatusResponse getMapStatus(
            @RequestParam("regionId") long regionId,
            @RequestParam(name = "unit", defaultValue = "CHILD") MapUnit unit) {
        return mapStatusService.findMapStatus(regionId, unit, LocalDateTime.now(KST));
    }
}
