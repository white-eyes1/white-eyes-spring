package com.whiteeyes.admin.camera.controller;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.dto.CameraDeleteResponse;
import com.whiteeyes.admin.camera.dto.CameraListItem;
import com.whiteeyes.admin.camera.dto.CameraResponse;
import com.whiteeyes.admin.camera.dto.CameraStartResponse;
import com.whiteeyes.admin.camera.dto.CameraStopResponse;
import com.whiteeyes.admin.camera.dto.CameraUpdateRequest;
import com.whiteeyes.admin.camera.service.CameraDeleteService;
import com.whiteeyes.admin.camera.service.CameraListService;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.camera.service.CameraStartService;
import com.whiteeyes.admin.camera.service.CameraStopService;
import com.whiteeyes.admin.camera.service.CameraUpdateService;

/** 캠 관리 API(FNC-A-018~023): 목록, 등록, 수정, 가동, 정지, 삭제. */
@RestController
@RequestMapping("/api/v1/manage/cameras")
@RequiredArgsConstructor
public class CameraApiController {

    // 기능별로 service를 나눠 두었고, 컨트롤러는 요청을 받아 해당 service에 넘기기만 한다.
    private final CameraRegisterService cameraRegisterService;
    private final CameraListService cameraListService;
    private final CameraStartService cameraStartService;
    private final CameraStopService cameraStopService;
    private final CameraUpdateService cameraUpdateService;
    private final CameraDeleteService cameraDeleteService;

    /**
     * 캠 목록 조회(FNC-A-022). regionId를 주면 그 지역(하위 포함)의 캠만, 없으면 전체를 돌려준다.
     *
     * @param regionId 지역 필터(선택)
     */
    @GetMapping
    public List<CameraListItem> list(@RequestParam(required = false) Long regionId) {
        return cameraListService.list(regionId);
    }

    /**
     * 캠 가동(FNC-A-020). 분석 서버에 시작을 지시하고 돌려받은 상태를 응답한다.
     *
     * @param id 캠 ID
     */
    @PostMapping("/{id}/start")
    public CameraStartResponse start(@PathVariable Long id) {
        return cameraStartService.start(id);
    }

    /** 캠 정지(FNC-A-021) */
    @PostMapping("/{id}/stop")
    public CameraStopResponse stop(@PathVariable Long id) {
        return cameraStopService.stop(id);
    }

    /** 캠 정보 수정(FNC-A-019): 정지 상태일 때만. 보낸 필드(이름·입력 방식·영상 주소)만 고친다. */
    @PatchMapping("/{id}")
    public CameraResponse update(@PathVariable Long id, @Valid @RequestBody CameraUpdateRequest request) {
        return cameraUpdateService.update(id, request);
    }

    /** 캠 삭제(FNC-A-023) */
    @DeleteMapping("/{id}")
    public CameraDeleteResponse delete(@PathVariable Long id) {
        return cameraDeleteService.delete(id);
    }

    /**
     * 캠 등록(FNC-A-018). 검증을 통과하면 새 자원을 만들었으므로 200이 아니라 201 Created로 응답한다.
     *
     * @param request 구역·이름·입력 방식·영상 주소(Bean Validation으로 형식 검증)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CameraResponse register(@Valid @RequestBody CameraCreateRequest request) {
        return cameraRegisterService.register(request);
    }
}
