package com.whiteeyes.common.stat.controller;

import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.DashboardSummaryResponse;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.service.DashboardSummaryService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/dashboard/summary
/** 현재 현황 요약(FNC-C-016) */
@RestController
@RequiredArgsConstructor
public class DashboardApiController {

    // '지금'을 서버 시간대가 아니라 항상 한국 시간으로 잡으려는 상수(통계 시각이 모두 KST 기준이다)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 요약 계산은 서비스가 맡는다
    private final DashboardSummaryService dashboardSummaryService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /** 구역 또는 지역의 현재 혼잡 현황(인원·밀집도·단계·유입·유출·오늘 이벤트 수 등)을 한 번에 돌려준다 */
    @GetMapping("/api/v1/dashboard/summary")
    public DashboardSummaryResponse getSummary(
            @RequestParam("targetType") DashboardTargetType targetType, @RequestParam("targetId") long targetId) {
        // 현재 시각을 컨트롤러에서 넘겨, 서비스가 시각을 직접 읽지 않게 한다(테스트에서 시각을 고정할 수 있다)
        return dashboardSummaryService.findSummary(targetType, targetId, LocalDateTime.now(KST));
    }
}
