package com.whiteeyes.common.stat.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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

// 두 앱이 함께 등록한다. 관리자 앱에서는 컨텍스트 경로가 붙어 /admin/api/v1/stats/export
/** 통계 엑셀 다운로드(FNC-C-023) */
@RestController
@RequiredArgsConstructor
public class StatExportApiController {

    // 엑셀(.xlsx) 파일의 표준 MIME 타입. 테스트에서도 같은 값을 확인하려고 package-private으로 둔다
    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // 엑셀 파일 생성은 서비스가 맡는다
    private final StatExportService statExportService;

    // 권한 '사용자 이상'은 접근 권한 검사(FNC-C-003)의 공통 규칙으로 붙는다.
    // common은 -parameters 없이 컴파일되므로 요청 값 이름을 애너테이션에 적는다
    /**
     * 조건에 맞는 통계를 엑셀로 만들어 응답 본문에 바로 내려 쓴다.
     * JSON이 아니라 파일을 돌려주므로 반환 타입이 void이고, 응답 스트림에 직접 쓴다.
     */
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
        // 조건 검증(대상·기간·항목)은 파일을 만들기 전에 끝낸다. 틀리면 여기서 400
        StatExportRequest request = StatExportRequest.parse(scope, targetId, interval, from, to, items, sum);
        // 서비스가 만든 임시 파일을 try-with-resources로 감싸, 응답을 다 쓴 뒤(오류가 나도) 반드시 정리한다
        try (StatExportFile file = statExportService.export(request)) {
            // 브라우저가 엑셀 파일로 다루도록 MIME 타입을 지정한다
            response.setContentType(XLSX_TYPE);
            response.setHeader(
                    // attachment로 두면 브라우저가 화면에 열지 않고 내려받는다. 파일 이름은 UTF-8로 인코딩해 한글 이름이 깨지지 않게 한다
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(file.fileName(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            // 파일 내용을 응답 스트림으로 흘려 보낸다(메모리에 통째로 올리지 않는다)
            file.writeTo(response.getOutputStream());
        }
    }
}
