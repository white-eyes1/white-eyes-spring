package com.whiteeyes.common.stat.controller;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.TrendResponse;
import com.whiteeyes.common.stat.service.TrendService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/dashboard/trend
/** 이용량 증감 요약(FNC-C-019) */
@RestController
public class TrendApiController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TrendService trendService;

    public TrendApiController(TrendService trendService) {
        this.trendService = trendService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/dashboard/trend")
    public TrendResponse getTrend(
            @RequestParam("targetType") DashboardTargetType targetType, @RequestParam("targetId") long targetId) {
        return trendService.findTrend(targetType, targetId, LocalDate.now(KST));
    }
}
