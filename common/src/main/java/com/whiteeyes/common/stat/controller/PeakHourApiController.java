package com.whiteeyes.common.stat.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.PeakHourItem;
import com.whiteeyes.common.stat.dto.PeakPeriod;
import com.whiteeyes.common.stat.service.PeakHourService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/dashboard/peak-hours
/** 피크 타임 TOP 3(FNC-C-029) */
@RestController
@RequiredArgsConstructor
public class PeakHourApiController {

    // '오늘'을 항상 한국 날짜로 잡기 위한 상수
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 피크 계산은 서비스가 맡는다
    private final PeakHourService peakHourService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다. period를 비우면 WEEK(FNC-C-029 입력 항목)
    /** 선택한 기간(어제까지) 평균 인원이 많았던 시간대 TOP 3을 돌려준다 */
    @GetMapping("/api/v1/dashboard/peak-hours")
    public List<PeakHourItem> getPeakHours(
            @RequestParam("targetType") DashboardTargetType targetType,
            @RequestParam("targetId") long targetId,
            @RequestParam(name = "period", defaultValue = "WEEK") PeakPeriod period) {
        // 기간은 '어제까지'로 정하므로 기준이 되는 오늘 날짜를 같이 넘긴다
        return peakHourService.findPeakHours(targetType, targetId, period, LocalDate.now(KST));
    }
}
