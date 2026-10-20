package com.whiteeyes.admin.region.controller;

import com.whiteeyes.admin.region.dto.ZonePreviewRequest;
import com.whiteeyes.admin.region.dto.ZonePreviewResponse;
import com.whiteeyes.admin.region.service.ZonePreviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manage/zones")
@RequiredArgsConstructor
public class ZoneApiController {

    private final ZonePreviewService zonePreviewService;

    @PostMapping("/preview")
    public ZonePreviewResponse preview(@Valid @RequestBody ZonePreviewRequest request) {
        return zonePreviewService.preview(request.boundary());
    }
}
