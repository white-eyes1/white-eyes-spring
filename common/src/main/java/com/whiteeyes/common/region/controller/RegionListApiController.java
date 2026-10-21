package com.whiteeyes.common.region.controller;

import com.whiteeyes.common.region.dto.RegionResponse;
import com.whiteeyes.common.region.dto.ZoneResponse;
import com.whiteeyes.common.region.service.RegionListService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/regions
// 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
/** 지역·구역 목록(FNC-C-008) */
@RestController
@RequiredArgsConstructor
public class RegionListApiController {

    private final RegionListService regionListService;

    @GetMapping("/api/v1/regions")
    public List<RegionResponse> getRegions(@RequestParam(name = "parentId", required = false) Long parentId) {
        return regionListService.findRegions(parentId);
    }

    @GetMapping("/api/v1/zones")
    public List<ZoneResponse> getZones(@RequestParam(name = "dongId", required = false) Long dongId) {
        return regionListService.findZones(dongId);
    }
}
