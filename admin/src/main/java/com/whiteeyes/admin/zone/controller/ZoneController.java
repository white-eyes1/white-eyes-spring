package com.whiteeyes.admin.zone.controller;

import com.whiteeyes.admin.zone.dto.ZoneCreateRequest;
import com.whiteeyes.admin.zone.dto.ZoneCreateResponse;
import com.whiteeyes.admin.zone.service.ZoneRegisterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manage/zones")
@RequiredArgsConstructor
public class ZoneController {

    private final ZoneRegisterService zoneRegisterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneCreateResponse register(@Valid @RequestBody ZoneCreateRequest request) {
        return zoneRegisterService.register(request.name(), request.boundary());
    }
}
