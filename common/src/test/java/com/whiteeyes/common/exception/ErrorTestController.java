package com.whiteeyes.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.ResourceAccessException;

@Controller
class ErrorTestController {

    record SampleRequest(@NotBlank(message = "필수입니다.") String name) {}

    @PostMapping("/api/v1/test/validation")
    @ResponseBody
    String validation(@Valid @RequestBody SampleRequest request) {
        return request.name();
    }

    @GetMapping("/api/v1/test/not-found")
    @ResponseBody
    String notFound() {
        throw new BusinessException(ErrorCode.NOT_FOUND, "구역을 찾을 수 없습니다.");
    }

    @GetMapping("/api/v1/test/zones/{id}")
    @ResponseBody
    String zone(@PathVariable("id") long id) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "구역을 찾을 수 없습니다.");
    }

    @GetMapping("/api/v1/test/positive/{id}")
    @ResponseBody
    String positive(@PathVariable("id") @Positive(message = "1 이상이어야 합니다.") long id) {
        return String.valueOf(id);
    }

    @GetMapping("/api/v1/test/page-size")
    @ResponseBody
    String pageSize(@RequestParam("size") int size) {
        return String.valueOf(size);
    }

    @GetMapping("/api/v1/test/analyzer")
    @ResponseBody
    String analyzer() {
        throw new ResourceAccessException("I/O error on POST request for http://10.0.0.5:5000");
    }

    @GetMapping("/api/v1/test/duplicate")
    @ResponseBody
    String duplicate() {
        throw new BusinessException(ErrorCode.DUPLICATE);
    }

    @GetMapping("/api/v1/test/status-conflict")
    @ResponseBody
    String statusConflict() {
        throw new BusinessException(ErrorCode.STATUS_CONFLICT);
    }

    @GetMapping("/api/v1/test/external")
    @ResponseBody
    String external() {
        throw new BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE);
    }

    @GetMapping("/api/v1/test/unexpected")
    @ResponseBody
    String unexpected() {
        throw new IllegalStateException("내부 비밀 정보");
    }

    @GetMapping("/internal/v1/test/not-found")
    @ResponseBody
    String internalNotFound() {
        throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    @GetMapping("/test/page/not-found")
    String pageNotFound() {
        throw new BusinessException(ErrorCode.NOT_FOUND, "구역을 찾을 수 없습니다.");
    }

    @GetMapping("/test/page/unexpected")
    String pageUnexpected() {
        throw new IllegalStateException("내부 비밀 정보");
    }
}
