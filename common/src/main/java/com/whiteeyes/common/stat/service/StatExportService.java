package com.whiteeyes.common.stat.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.service.WorkRecorder;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.stat.dto.StatExportItem;
import com.whiteeyes.common.stat.dto.StatExportPlan;
import com.whiteeyes.common.stat.dto.StatExportRequest;

/**
 * 통계 엑셀 다운로드(FNC-C-023). 대상·행 수를 확인하고 파일을 다 만든 뒤 작업 기록을 남긴다(처리 순서 4).
 *
 * <p>값 읽기는 묶음마다 따로 조회하므로(StatExportReader) 전체를 트랜잭션 하나로 감싸지 않고, 작업 기록만 짧은
 * 트랜잭션으로 저장한다. 파일을 만들다 실패하면 기록하지 않는다. 오류(400·404)는 응답을 쓰기 전에 나므로 JSON 오류로 나간다.
 */
@Service
public class StatExportService {

    private static final String EXPORT_KIND = "STATS";

    private final StatExportReader statExportReader;
    private final WorkRecorder workRecorder;
    private final TransactionTemplate transactionTemplate;

    public StatExportService(
            StatExportReader statExportReader, WorkRecorder workRecorder, TransactionTemplate transactionTemplate) {
        this.statExportReader = statExportReader;
        this.workRecorder = workRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 엑셀 파일을 만든다. 다 쓰면 {@link StatExportFile#close()}로 임시 파일을 지운다.
     *
     * @throws com.whiteeyes.common.exception.BusinessException 로그인하지 않았으면(401), 대상이 없으면(404),
     *     한 시트가 100만 행을 넘으면(400 TOO_MANY_ROWS)
     */
    public StatExportFile export(StatExportRequest request) throws IOException {
        LoginAccount actor = workRecorder.requireActor();
        StatExportPlan plan = statExportReader.plan(request);
        StatExcelWriter writer = new StatExcelWriter(plan);
        try {
            statExportReader.read(plan, writer::append);
            transactionTemplate.executeWithoutResult(status -> record(actor, plan));
            return new StatExportFile(StatExcelWriter.fileName(plan), writer);
        } catch (RuntimeException e) {
            writer.close();
            throw e;
        }
    }

    // detail: 종류·scope·대상·기간·단위·항목(FNC-C-023 처리 순서 4)
    private void record(LoginAccount actor, StatExportPlan plan) {
        StatExportRequest request = plan.request();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", EXPORT_KIND);
        detail.put("scope", request.scope().name());
        detail.put("targetId", request.targetId());
        detail.put("from", request.from().toString());
        detail.put("to", request.to().toString());
        detail.put("interval", request.interval().getCode());
        detail.put("items", request.items().stream().map(StatExportItem::name).toList());
        detail.put("sum", request.sum());
        workRecorder.record(
                actor,
                WorkAction.EXCEL_DOWNLOAD,
                WorkTargetType.valueOf(request.scope().name()),
                request.targetId(),
                plan.targetName(),
                detail);
    }

    /** 다 만든 엑셀 파일. 파일 이름은 stats_{대상이름}_{from}_{to}.xlsx(처리 순서 5). */
    public record StatExportFile(String fileName, StatExcelWriter writer) implements AutoCloseable {

        public void writeTo(OutputStream out) throws IOException {
            writer.writeTo(out);
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
