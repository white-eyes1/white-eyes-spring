package com.whiteeyes.common.stat.controller;

import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.CompareRequest;
import com.whiteeyes.common.stat.dto.CompareResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.service.CompareService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/compare
/** 기간 비교 분석(FNC-C-020) */
@RestController
public class CompareApiController {

    private final CompareService compareService;

    public CompareApiController(CompareService compareService) {
        this.compareService = compareService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/compare")
    public CompareResponse compare(
            @RequestParam("targetType") DashboardTargetType targetType,
            @RequestParam("targetId") long targetId,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(value = "baseFrom", required = false) String baseFrom,
            @RequestParam(value = "baseTo", required = false) String baseTo,
            @RequestParam(value = "basePreset", required = false) String basePreset,
            @RequestParam(value = "thresholdPct", required = false) Integer thresholdPct)
            throws BindException {
        return compareService.compare(
                CompareRequest.parse(targetType, targetId, from, to, baseFrom, baseTo, basePreset, thresholdPct));
    }
}
