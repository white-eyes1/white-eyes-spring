/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/camera/controller/CameraInternalController.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025), API: PATCH /internal/v1/cameras/{id}/status (api/analyzer.md)
 *
 * [무엇] 분석 서버가 캠 상태를 알려 주는 HTTP 입구.
 * [이름] 내부 API(8082)용 컨트롤러는 'InternalController'(code-conventions 3-2의 예시가 바로 CameraInternalController).
 * [하는 일] 입력 형식 검증(@Valid) → 서비스 호출 → 204. 판단·DB 접근은 하지 않는다(package-structure 3-1).
 * [아직 없는 것] X-Internal-Token 검사(401), 8082 포트 연결. 지금은 8081의 /admin 컨텍스트 아래에만 열려 있다.
 * [나중에 #16의 컨트롤러와 합쳐질 때] 같은 '/internal/v1/cameras' 아래에 다른 내부 API(가동 대상 목록, FNC-A-027)도
 *   들어올 예정이라 클래스 수준 경로를 /internal/v1/cameras로 잡았다.
 */
package com.whiteeyes.admin.camera.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.service.CameraStatusService;

@RestController
@RequestMapping("/internal/v1/cameras")
public class CameraInternalController {

    private final CameraStatusService cameraStatusService;

    public CameraInternalController(CameraStatusService cameraStatusService) {
        this.cameraStatusService = cameraStatusService;
    }

    // [해석] PATCH: 캠 전체를 바꾸는 게 아니라 '상태 일부'만 바꾸는 요청이라 PUT이 아니라 PATCH(계약 그대로).
    // [해석] @PathVariable("id") Long cameraId: URL의 {id}를 받되, 코드에서는 무슨 id인지 드러나게 cameraId로 부른다.
    //        이름("id")을 명시한 이유: 변수 이름(cameraId)과 경로 이름(id)이 달라서. 생략하면 컴파일 옵션에 따라 매칭이 실패할 수 있다.
    // [해석] @Valid @RequestBody: JSON 본문을 DTO로 바꾸고 DTO의 검증 규칙(@NotNull, @Min, @Max, @Size)을 돌린다.
    //        어기면 서비스까지 가지 않고 400.
    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> reportStatus(
            @PathVariable("id") Long cameraId, @Valid @RequestBody CameraStatusUpdateRequest request) {
        cameraStatusService.reportStatus(cameraId, request);
        // [해석] 계약상 성공은 204(본문 없음). 없는 캠이면 서비스가 던진 CameraNotFoundException이 404로 바뀐다.
        return ResponseEntity.noContent().build();
    }
}
