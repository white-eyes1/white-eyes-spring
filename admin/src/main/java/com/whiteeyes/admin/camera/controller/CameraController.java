package com.whiteeyes.admin.camera.controller;

import com.whiteeyes.admin.camera.dto.CameraCreateRequest;
import com.whiteeyes.admin.camera.dto.CameraListItem;
import com.whiteeyes.admin.camera.dto.CameraResponse;
import com.whiteeyes.admin.camera.dto.CameraStartResponse;
import com.whiteeyes.admin.camera.dto.CameraStopResponse;
import com.whiteeyes.admin.camera.service.CameraListService;
import com.whiteeyes.admin.camera.service.CameraRegisterService;
import com.whiteeyes.admin.camera.service.CameraStartService;
import com.whiteeyes.admin.camera.service.CameraStopService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manage/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraRegisterService cameraRegisterService;
    private final CameraListService cameraListService;
    private final CameraStartService cameraStartService;
    private final CameraStopService cameraStopService;

    @GetMapping
    public List<CameraListItem> list(@RequestParam(required = false) Long regionId) {
        return cameraListService.list(regionId);
    }

    @PostMapping("/{id}/start")
    public CameraStartResponse start(@PathVariable Long id) {
        return cameraStartService.start(id);
    }

    /** 캠 정지(FNC-A-021) */
    @PostMapping("/{id}/stop")
    public CameraStopResponse stop(@PathVariable Long id) {
        return cameraStopService.stop(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CameraResponse register(@Valid @RequestBody CameraCreateRequest request) {
        return cameraRegisterService.register(request);
    }
}
