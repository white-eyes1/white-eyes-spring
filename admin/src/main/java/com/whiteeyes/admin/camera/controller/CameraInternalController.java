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

/** 캠 상태 보고 수신(FNC-A-025) */
@RestController
@RequestMapping("/internal/v1/cameras")
public class CameraInternalController {

    private final CameraStatusService cameraStatusService;

    public CameraInternalController(CameraStatusService cameraStatusService) {
        this.cameraStatusService = cameraStatusService;
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> reportStatus(
            @PathVariable("id") Long cameraId, @Valid @RequestBody CameraStatusUpdateRequest request) {
        cameraStatusService.reportStatus(cameraId, request);
        return ResponseEntity.noContent().build();
    }
}
