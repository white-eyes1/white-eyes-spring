package com.whiteeyes.common.stat.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.HourlyLevelItem;
import com.whiteeyes.common.stat.service.HourlyLevelService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/dashboard/hourly-levels
/** 시간대별 혼잡 단계(FNC-C-018) */
@RestController
public class HourlyLevelApiController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HourlyLevelService hourlyLevelService;

    public HourlyLevelApiController(HourlyLevelService hourlyLevelService) {
        this.hourlyLevelService = hourlyLevelService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/dashboard/hourly-levels")
    public List<HourlyLevelItem> getHourlyLevels(
            @RequestParam("targetType") DashboardTargetType targetType,
            @RequestParam("targetId") long targetId,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        LocalDateTime now = LocalDateTime.now(KST);
        // date를 비우면 오늘(FNC-C-018 입력 항목 'date(기본 오늘)')
        return hourlyLevelService.findHourlyLevels(targetType, targetId, date == null ? now.toLocalDate() : date, now);
    }
}
