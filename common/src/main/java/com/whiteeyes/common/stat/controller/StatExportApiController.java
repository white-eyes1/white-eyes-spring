package com.whiteeyes.common.stat.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.common.stat.dto.StatExportRequest;
import com.whiteeyes.common.stat.dto.StatExportScope;
import com.whiteeyes.common.stat.service.StatExportService;
import com.whiteeyes.common.stat.service.StatExportService.StatExportFile;

import jakarta.servlet.http.HttpServletResponse;

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/stats/export
/** 통계 엑셀 다운로드(FNC-C-023) */
@RestController
public class StatExportApiController {

    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final StatExportService statExportService;

    public StatExportApiController(StatExportService statExportService) {
        this.statExportService = statExportService;
    }

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    @GetMapping("/api/v1/stats/export")
    public void export(
            @RequestParam("scope") StatExportScope scope,
            @RequestParam(name = "targetId", required = false) Long targetId,
            @RequestParam("interval") String interval,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(name = "items", required = false) List<String> items,
            @RequestParam(name = "sum", defaultValue = "false") boolean sum,
            HttpServletResponse response)
            throws BindException, IOException {
        StatExportRequest request = StatExportRequest.parse(scope, targetId, interval, from, to, items, sum);
        try (StatExportFile file = statExportService.export(request)) {
            response.setContentType(XLSX_TYPE);
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(file.fileName(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            file.writeTo(response.getOutputStream());
        }
    }
}
