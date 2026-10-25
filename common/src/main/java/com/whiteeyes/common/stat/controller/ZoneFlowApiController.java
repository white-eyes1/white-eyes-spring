package com.whiteeyes.common.stat.controller;

import java.util.List;

import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.ZoneFlowItem;
import com.whiteeyes.common.stat.dto.ZoneFlowRequest;
import com.whiteeyes.common.stat.service.ZoneFlowService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/map/flows
/** 구역 간 이동 흐름(FNC-C-010) */
@RestController
public class ZoneFlowApiController {

    private final ZoneFlowService zoneFlowService;

    public ZoneFlowApiController(ZoneFlowService zoneFlowService) {
        this.zoneFlowService = zoneFlowService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/map/flows")
    public List<ZoneFlowItem> getFlows(
            @RequestParam("regionId") long regionId, @RequestParam("from") String from, @RequestParam("to") String to)
            throws BindException {
        return zoneFlowService.findFlows(ZoneFlowRequest.parse(regionId, from, to));
    }
}
