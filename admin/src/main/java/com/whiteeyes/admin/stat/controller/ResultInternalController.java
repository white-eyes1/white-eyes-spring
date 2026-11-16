package com.whiteeyes.admin.stat.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.service.ResultReceiveService;

/** 분석 결과 수신(FNC-A-024) */
@RestController
@RequestMapping("/internal/v1/results")
@RequiredArgsConstructor
public class ResultInternalController {

    // 분석 서버가 한 번에 보내는 최대 건수(api/analyzer.md)
    private static final int MAX_RESULTS_PER_REQUEST = 2000;

    // 실제 처리(캠→구역 확인, 1분 누적기 적재)는 service가 한다
    private final ResultReceiveService resultReceiveService;

    /**
     * 분석 서버가 보낸 1초 단위 결과 묶음을 받는다. 처리 결과는 본문 없이 204로만 알린다.
     */
    @PostMapping
    public ResponseEntity<Void> receiveResults(
            @RequestBody @Size(max = MAX_RESULTS_PER_REQUEST) List<@Valid ResultReceiveRequest> results) {
        // 캠 확인에 실패하거나 이미 마감된 분의 결과는 service가 조용히 버린다(분석 서버에 오류로 돌려주지 않음)
        resultReceiveService.receiveResults(results);
        // 204 No Content
        return ResponseEntity.noContent().build();
    }
}
