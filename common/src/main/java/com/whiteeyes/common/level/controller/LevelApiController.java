package com.whiteeyes.common.level.controller;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.level.dto.LevelResponse;
import com.whiteeyes.common.level.service.LevelService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/meta/levels
/** 혼잡 단계 판정(FNC-C-025) */
@RestController
@RequiredArgsConstructor
public class LevelApiController {

    // 혼잡 단계 기준을 계산하는 서비스
    private final LevelService levelService;

    // 혼잡 단계 기준(FNC-C-025). 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
    /** 단계 번호·이름·색·시작 밀집도 목록을 돌려준다. 화면 범례와 혼잡 기준 표시에 쓴다. 기준은 환경변수로 정한 공통 값이라 요청마다 달라지지 않는다. */
    @GetMapping("/api/v1/meta/levels")
    public List<LevelResponse> getLevels() {
        // 계산은 서비스가 하고 컨트롤러는 결과를 그대로 응답한다
        return levelService.findLevelCriteria();
    }
}
