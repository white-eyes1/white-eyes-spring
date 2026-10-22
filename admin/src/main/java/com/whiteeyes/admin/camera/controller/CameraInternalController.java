package com.whiteeyes.admin.camera.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.camera.dto.CameraStatusUpdateRequest;
import com.whiteeyes.admin.camera.dto.DesiredCameraResponse;
import com.whiteeyes.admin.camera.service.CameraStatusService;
import com.whiteeyes.admin.camera.service.DesiredCameraService;

/** 분석 서버가 부르는 캠 내부 API: 상태 보고 수신(FNC-A-025), 가동 대상 캠 목록(FNC-A-027) */
@RestController
@RequestMapping("/internal/v1/cameras")
public class CameraInternalController {

    private final CameraStatusService cameraStatusService;
    private final DesiredCameraService desiredCameraService;

    public CameraInternalController(
            CameraStatusService cameraStatusService, DesiredCameraService desiredCameraService) {
        this.cameraStatusService = cameraStatusService;
        this.desiredCameraService = desiredCameraService;
    }

    /** 분석 서버가 다시 켜졌을 때 가동할 캠 목록(FNC-A-027) */
    @GetMapping("/desired")
    public List<DesiredCameraResponse> desiredCameras() {
        return desiredCameraService.findDesiredCameras();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> reportStatus(
            @PathVariable("id") Long cameraId, @Valid @RequestBody CameraStatusUpdateRequest request) {
        cameraStatusService.reportStatus(cameraId, request);
        return ResponseEntity.noContent().build();
    }
}
