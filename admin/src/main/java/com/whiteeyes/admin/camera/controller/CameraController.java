package com.whiteeyes.admin.camera.controller;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.dto.CameraResponse;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manage/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraRegisterService cameraRegisterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CameraResponse register(@Valid @RequestBody CameraCreateRequest request) {
        return cameraRegisterService.register(request);
    }
}
