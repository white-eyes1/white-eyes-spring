package com.whiteeyes.admin.stat.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.service.ResultReceiveService;

@RestController
@RequestMapping("/internal/v1/results")
public class ResultInternalController {

    // 분석 서버가 한 번에 보내는 최대 건수(api/analyzer.md)
    private static final int MAX_RESULTS_PER_REQUEST = 2000;

    private final ResultReceiveService resultReceiveService;

    public ResultInternalController(ResultReceiveService resultReceiveService) {
        this.resultReceiveService = resultReceiveService;
    }

    @PostMapping
    public ResponseEntity<Void> receiveResults(
            @RequestBody @Size(max = MAX_RESULTS_PER_REQUEST) List<@Valid ResultReceiveRequest> results) {
        resultReceiveService.receiveResults(results);
        return ResponseEntity.noContent().build();
    }
}
