/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/controller/RecordingInternalController.java
 * 관련 이슈: #17(FNC-A-026), API: POST /internal/v1/recordings (api/analyzer.md)
 *
 * [무엇] 분석 서버가 완성한 녹화를 등록하는 HTTP 입구.
 * [이름·위치] 내부 API(8082)용이라 'InternalController'(code-conventions 3-2). 녹화는 media 도메인이라 media/controller에 둔다
 *   (package-structure.md: media는 "녹화 등록 내부 API" 포함).
 * [하는 일] 형식 검증(@Valid) → 서비스 호출 → 201. 캠 확인·규칙 판단은 서비스가 한다.
 * [아직 없는 것] X-Internal-Token 검사(401)와 8082 포트 연결(#15·#16과 같음).
 */
package com.whiteeyes.admin.media.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.service.RecordingRegisterService;

@RestController
@RequestMapping("/internal/v1/recordings")
public class RecordingInternalController {

    private final RecordingRegisterService recordingRegisterService;

    public RecordingInternalController(RecordingRegisterService recordingRegisterService) {
        this.recordingRegisterService = recordingRegisterService;
    }

    // [해석] POST: 새 녹화를 '만드는' 요청. 성공 응답은 계약대로 201 Created + {"id": …}.
    //        #15·#16의 204(본문 없음)와 다른 이유: 분석 서버가 등록된 녹화 번호를 돌려받아야 하기 때문(계약에 {id} 응답이 있음).
    //        오류 응답: 형식 오류(@Valid) 400 / 규칙 위반(RecordingRegisterException) 400 / 없는 캠(CameraNotFoundException) 404.
    @PostMapping
    public ResponseEntity<RecordingRegisterResponse> registerRecording(
            @Valid @RequestBody RecordingRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recordingRegisterService.registerRecording(request));
    }
}
