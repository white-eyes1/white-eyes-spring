package com.whiteeyes.common.stat.controller;

import java.time.LocalDate;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.VisitPatternRequest;
import com.whiteeyes.common.stat.dto.VisitPatternResponse;
import com.whiteeyes.common.stat.service.VisitPatternService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/dashboard/visit-pattern
/** 방문 패턴(FNC-C-028) */
@RestController
@RequiredArgsConstructor
public class VisitPatternApiController {

    // '오늘'을 항상 한국 날짜로 잡기 위한 상수
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 요일별·시간대별 평균 계산은 서비스가 맡는다
    private final VisitPatternService visitPatternService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /** 기간의 요일(월~일) × 시간(0~23) 평균 인원과 평일·주말 시간대별 평균을 돌려준다. from·to를 비우면 어제까지 28일 */
    @GetMapping("/api/v1/dashboard/visit-pattern")
    public VisitPatternResponse getVisitPattern(
            @RequestParam("targetType") DashboardTargetType targetType,
            @RequestParam("targetId") long targetId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to)
            throws BindException {
        // 기간을 비웠을 때의 기본값(어제까지 28일)을 정하려면 오늘 날짜가 필요해 같이 넘긴다
        VisitPatternRequest request = VisitPatternRequest.parse(targetType, targetId, from, to, LocalDate.now(KST));
        return visitPatternService.findPattern(request);
    }
}
