package com.whiteeyes.common.stat.controller;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CompareApiController {

    // 비교 계산은 서비스가 맡고, 컨트롤러는 요청 값 검증·변환만 한다
    private final CompareService compareService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /**
     * 대상 기간과 기준 기간의 요일×시간(168칸) 평균 인원을 비교해 증감률과 비정상 여부를 돌려준다.
     * 기준 기간은 baseFrom·baseTo 또는 basePreset 중 하나만 보낸다. 날짜·프리셋을 문자열로 받는 것은
     * 형식 오류를 칸별 오류(400)로 직접 만들어 돌려주려는 것이다.
     */
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
        // 문자열 요청 값을 CompareRequest.parse가 읽고 검사한 뒤 서비스에 넘긴다(잘못된 값은 여기서 400으로 끝난다)
        return compareService.compare(
                CompareRequest.parse(targetType, targetId, from, to, baseFrom, baseTo, basePreset, thresholdPct));
    }
}
