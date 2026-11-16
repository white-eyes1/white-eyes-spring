package com.whiteeyes.common.stat.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class StatExportService {

    // 작업 기록의 detail에 남기는 엑셀 종류 표시
    private static final String EXPORT_KIND = "STATS";

    // 대상 확인(계획)과 통계 읽기를 맡는다
    private final StatExportReader statExportReader;
    // 작업 기록 저장과 현재 로그인 계정 확인을 맡는다
    private final WorkRecorder workRecorder;
    // 작업 기록만 짧은 트랜잭션으로 저장하려고 직접 트랜잭션을 연다(@Transactional을 메서드 전체에 걸면 긴 읽기 동안 연결을 잡고 있게 된다)
    private final TransactionTemplate transactionTemplate;

    /**
     * 엑셀 파일을 만든다. 다 쓰면 {@link StatExportFile#close()}로 임시 파일을 지운다.
     *
     * @throws com.whiteeyes.common.exception.BusinessException 로그인하지 않았으면(401), 대상이 없으면(404),
     *     한 시트가 100만 행을 넘으면(400 TOO_MANY_ROWS)
     */
    public StatExportFile export(StatExportRequest request) throws IOException {
        // 누가 내려받았는지 기록해야 하므로 로그인하지 않았으면 먼저 401로 끝낸다(파일을 만드는 비용을 쓰기 전에)
        LoginAccount actor = workRecorder.requireActor();
        // 대상 존재 확인·행 수 한도 확인·시트 구성(대상 줄)을 먼저 끝낸다. 문제가 있으면 파일을 만들기 전에 400·404가 난다
        StatExportPlan plan = statExportReader.plan(request);
        // 엑셀을 임시 파일에 쓰는 작성기. 행을 한 줄씩 흘려 써서 100만 행이어도 메모리를 아낀다
        StatExcelWriter writer = new StatExcelWriter(plan);
        try {
            // 통계를 묶음 단위로 읽어 가며 작성기에 한 행씩 넘긴다
            statExportReader.read(plan, writer::append);
            // 파일을 끝까지 만든 뒤에만 작업 기록을 남긴다(실패한 다운로드가 기록되지 않게)
            transactionTemplate.executeWithoutResult(status -> record(actor, plan));
            // 성공하면 파일을 닫지 않고 호출자에게 넘긴다. 응답을 다 쓴 뒤 호출자가 close()로 임시 파일을 지운다
            return new StatExportFile(StatExcelWriter.fileName(plan), writer);
            // 중간에 실패하면 호출자가 파일을 받지 못해 닫을 수 없으므로 여기서 임시 파일을 정리하고 예외를 그대로 던진다
        } catch (RuntimeException e) {
            writer.close();
            throw e;
        }
    }

    // detail: 종류·scope·대상·기간·단위·항목(FNC-C-023 처리 순서 4)
    private void record(LoginAccount actor, StatExportPlan plan) {
        StatExportRequest request = plan.request();
        // 작업 기록에 남길 조건 모음. 순서를 유지하려고 LinkedHashMap을 쓴다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", EXPORT_KIND);
        detail.put("scope", request.scope().name());
        detail.put("targetId", request.targetId());
        detail.put("from", request.from().toString());
        detail.put("to", request.to().toString());
        detail.put("interval", request.interval().getCode());
        detail.put("items", request.items().stream().map(StatExportItem::name).toList());
        detail.put("sum", request.sum());
        // 대상 종류는 엑셀 범위(ZONE·REGION·ALL) 이름과 작업 대상 종류 이름이 같다는 전제로 변환한다
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

        /** 임시 파일의 내용을 출력 스트림(응답)으로 복사한다 */
        public void writeTo(OutputStream out) throws IOException {
            writer.writeTo(out);
        }

        @Override
        // try-with-resources가 끝날 때 작성기를 닫아 임시 파일을 지운다
        public void close() throws IOException {
            writer.close();
        }
    }
}
