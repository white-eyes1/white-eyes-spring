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

    @PostMapping
    public ResponseEntity<RecordingRegisterResponse> registerRecording(
            @Valid @RequestBody RecordingRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recordingRegisterService.registerRecording(request));
    }
}
