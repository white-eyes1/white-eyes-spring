package com.whiteeyes.common.level.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.level.dto.LevelResponse;
import com.whiteeyes.common.level.service.LevelService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/meta/levels
@RestController
public class LevelApiController {

    private final LevelService levelService;

    public LevelApiController(LevelService levelService) {
        this.levelService = levelService;
    }

    // 혼잡 단계 기준(FNC-C-025). 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
    @GetMapping("/api/v1/meta/levels")
    public List<LevelResponse> getLevels() {
        return levelService.findLevelCriteria();
    }
}
