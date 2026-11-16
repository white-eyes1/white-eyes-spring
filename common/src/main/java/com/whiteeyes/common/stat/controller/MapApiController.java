package com.whiteeyes.common.stat.controller;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.MapStatusResponse;
import com.whiteeyes.common.stat.dto.MapUnit;
import com.whiteeyes.common.stat.service.MapStatusService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/map/status
/** 혼잡 단계 색 표시(FNC-C-009) */
@RestController
@RequiredArgsConstructor
public class MapApiController {

    // '지금'을 항상 한국 시간으로 잡기 위한 상수
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 지도 현황 계산은 서비스가 맡는다
    private final MapStatusService mapStatusService;

    // 혼잡 단계 현황(FNC-C-009). 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /** 지역 아래 단위(하위 지역 또는 구역)별 최근 혼잡 현황을 돌려준다. unit이 CHILD면 바로 아래 단위, ZONE이면 모든 구역 */
    @GetMapping("/api/v1/map/status")
    public MapStatusResponse getMapStatus(
            @RequestParam("regionId") long regionId,
            @RequestParam(name = "unit", defaultValue = "CHILD") MapUnit unit) {
        // 최근 5분 이내 통계를 판단하는 기준 시각으로 현재 시각을 넘긴다
        return mapStatusService.findMapStatus(regionId, unit, LocalDateTime.now(KST));
    }
}
