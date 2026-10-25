package com.whiteeyes.common.stat.controller;

import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.StatChildrenRequest;
import com.whiteeyes.common.stat.dto.StatChildrenResponse;
import com.whiteeyes.common.stat.service.StatChildrenService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/stats/children
/** 여러 지역 비교(FNC-C-014) */
@RestController
public class StatChildrenApiController {

    private final StatChildrenService statChildrenService;

    public StatChildrenApiController(StatChildrenService statChildrenService) {
        this.statChildrenService = statChildrenService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/stats/children")
    public StatChildrenResponse getChildren(
            @RequestParam("regionId") long regionId,
            @RequestParam("item") String item,
            @RequestParam("interval") String interval,
            @RequestParam("from") String from,
            @RequestParam("to") String to)
            throws BindException {
        return statChildrenService.findChildren(StatChildrenRequest.parse(regionId, item, interval, from, to));
    }
}
