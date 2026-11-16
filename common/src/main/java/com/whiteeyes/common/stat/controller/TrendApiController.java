package com.whiteeyes.common.stat.controller;

import java.time.LocalDate;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.TrendResponse;
import com.whiteeyes.common.stat.service.TrendService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/dashboard/trend
/** 이용량 증감 요약(FNC-C-019) */
@RestController
@RequiredArgsConstructor
public class TrendApiController {

    // '오늘'을 항상 한국 날짜로 잡기 위한 상수
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 증감 계산은 서비스가 맡는다
    private final TrendService trendService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /** 어제까지 7일의 이용량을 1주·4주·52주 전 같은 7일과 비교한 증감률을 돌려준다 */
    @GetMapping("/api/v1/dashboard/trend")
    public TrendResponse getTrend(
            @RequestParam("targetType") DashboardTargetType targetType, @RequestParam("targetId") long targetId) {
        // 기준이 되는 오늘 날짜를 넘겨 서비스가 '어제까지 7일'을 계산하게 한다
        return trendService.findTrend(targetType, targetId, LocalDate.now(KST));
    }
}
