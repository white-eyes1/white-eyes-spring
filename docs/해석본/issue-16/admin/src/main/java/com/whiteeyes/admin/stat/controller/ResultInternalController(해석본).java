/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/controller/ResultInternalController.java
 * 관련 이슈: #16(FNC-A-024), API: POST /internal/v1/results (api/analyzer.md)
 *
 * [무엇] 분석 서버가 1초마다 보내는 결과 묶음을 받는 입구.
 * [이름] 내부 API(8082) 컨트롤러는 'InternalController' 접미사(code-conventions 3-2). 화면용(PageController),
 *   브라우저 API(ApiController)와 이름만 보고 구분된다. 나중에 8082 포트·토큰 검사를 붙일 때도 이 이름으로 찾기 쉽다.
 * [하는 일과 하지 않는 일] 형식 검증과 서비스 호출만 한다. 버릴지 말지 같은 판단은 하지 않는다(package-structure 3-1).
 * [아직 없는 것] X-Internal-Token 검사(401)와 8082 포트 연결. /internal/** 공통 인증 이슈에서 붙는다.
 *   지금은 8081의 /admin 컨텍스트 아래(/admin/internal/v1/results)로만 열려 있다.
 */
package com.whiteeyes.admin.stat.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.service.ResultReceiveService;

@RestController
@RequestMapping("/internal/v1/results")
public class ResultInternalController {

    // 분석 서버가 한 번에 보내는 최대 건수(api/analyzer.md)
    // [해석] 계약에 "최대 2,000건"이 있다. 분석 서버는 밀린 결과를 2,000건씩 나눠 보내므로(FNC-S-006 처리 순서 4)
    //        그보다 큰 묶음은 계약 위반 = 형식 오류(400). 메모리를 지나치게 쓰는 요청도 입구에서 막는다.
    //        어노테이션 값으로 쓰려면 컴파일 시점 상수여야 해서 static final로 뒀다.
    private static final int MAX_RESULTS_PER_REQUEST = 2000;

    private final ResultReceiveService resultReceiveService;

    public ResultInternalController(ResultReceiveService resultReceiveService) {
        this.resultReceiveService = resultReceiveService;
    }

    // [해석] 검증이 두 겹이다.
    //        @Size(max = 2000): 목록 자체의 크기.
    //        List<@Valid ResultReceiveRequest>: 목록 안의 항목 하나하나에 DTO의 규칙(@NotNull, @PositiveOrZero…)을 적용.
    //        목록에 @Valid만 붙이면 항목까지 검사하지 않기 때문에 타입 안쪽(List<@Valid …>)에 붙였다.
    //        Spring 6.1부터는 이렇게 파라미터에 제약을 붙이면 메서드 검증이 자동으로 돌고, 위반이면 400으로 응답한다.
    //        항목 하나라도 틀리면 묶음 전체가 400: 명세 "형식 오류: 400(분석 서버는 버림)" — 분석 서버가 묶음 단위로 버리므로
    //        관리자 앱도 묶음 단위로 거절해야 일부만 반영되는 어중간한 상태가 생기지 않는다.
    @PostMapping
    public ResponseEntity<Void> receiveResults(
            @RequestBody @Size(max = MAX_RESULTS_PER_REQUEST) List<@Valid ResultReceiveRequest> results) {
        resultReceiveService.receiveResults(results);
        // [해석] 계약상 성공 응답은 204 No Content(본문 없음).
        return ResponseEntity.noContent().build();
    }
}
