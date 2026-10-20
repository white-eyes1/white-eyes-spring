/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/level/controller/LevelApiController.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] GET /api/v1/meta/levels. 화면의 혼잡 기준 접기 영역(SCR-C-003)과 지도·관리자 헤더 범례(SCR-A-013)가
 *   단계 이름·색·경계값을 받아 가는 API.
 * [왜 common에] conventions.md '공통 API 규칙': 공통 API는 두 앱에 같은 경로로 있고, 공통 모듈의 컨트롤러를 두 앱이 함께
 *   등록한다. 두 앱의 시작 클래스가 com.whiteeyes.common 패키지를 스캔하므로 따로 등록할 필요가 없다.
 * [하지 않은 것] 권한 '사용자 이상(두 앱)'. 아직 Spring Security 설정(FNC-C-001·003)이 dev에 없어서, 지금은 로그인 없이도
 *   열린다. 접근 권한 검사 이슈에서 /api/** 전체 규칙으로 붙을 예정이라 이 컨트롤러에 따로 넣지 않았다.
 */
package com.whiteeyes.common.level.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.level.dto.LevelResponse;
import com.whiteeyes.common.level.service.LevelService;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/meta/levels
// [해석] 관리자 앱은 server.servlet.context-path가 /admin이라, 같은 매핑이 브라우저 주소로는 /admin/api/v1/meta/levels가 된다.
//        api/public.md 머리말의 "관리자 앱 API는 /admin 접두사가 붙는다"와 맞다.
//        클래스 이름은 code-conventions의 REST 컨트롤러 접미사 'ApiController'를 따랐다.
@RestController
public class LevelApiController {

    private final LevelService levelService;

    public LevelApiController(LevelService levelService) {
        this.levelService = levelService;
    }

    // 혼잡 단계 기준(FNC-C-025). 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다
    // [해석] 컨트롤러는 서비스를 부르고 결과를 돌려주기만 한다(package-structure.md: controller는 비즈니스 판단을 하지 않음).
    //        List를 돌려주면 @RestController가 JSON 배열로 바꿔 200으로 응답한다. 명세상 오류 응답은 없다.
    @GetMapping("/api/v1/meta/levels")
    public List<LevelResponse> getLevels() {
        return levelService.findLevelCriteria();
    }
}
