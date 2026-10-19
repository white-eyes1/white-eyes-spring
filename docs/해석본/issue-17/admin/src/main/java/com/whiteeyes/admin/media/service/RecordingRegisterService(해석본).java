/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/service/RecordingRegisterService.java
 * 관련 이슈: #17(FNC-A-026 녹화 등록 수신)
 *
 * [무엇] 녹화 등록의 업무 로직. 흐름은 한 방향이다:
 *   ① 캠 확인(없거나 삭제 → 404) → ② endedAt 규칙 → ③ 경로 규칙 → ④ 저장 → ⑤ 더미면 이전 반복 닫기 → ⑥ id 반환
 * [왜 이 순서인가] 검사(①~③)를 저장(④)보다 모두 앞에 두어, 규칙을 어긴 요청은 DB에 아무것도 남기지 않는다.
 *   ①을 맨 앞에 둔 이유: ②·③의 규칙이 캠의 입력 방식(LIVE·DUMMY)에 따라 달라서, 그 값을 먼저 알아야 한다.
 * [염두에 둔 것]
 *   - 다른 도메인(camera)은 CameraQueryService로만 부른다(package-structure 3-2). CameraRepository를 직접 쓰지 않는다.
 *   - ④와 ⑤를 한 트랜잭션(@Transactional)으로 묶었다. 둘 중 하나라도 실패하면 둘 다 취소되어 '새 녹화는 들어갔는데
 *     이전 반복은 안 닫힌' 어중간한 상태가 남지 않는다.
 */
package com.whiteeyes.admin.media.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.camera.exception.CameraNotFoundException;
import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.media.dto.RecordingRegisterRequest;
import com.whiteeyes.admin.media.dto.RecordingRegisterResponse;
import com.whiteeyes.admin.media.entity.Recording;
import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.admin.media.repository.RecordingRepository;
import com.whiteeyes.common.camera.entity.CameraSourceType;

@Service
public class RecordingRegisterService {

    private static final Logger log = LoggerFactory.getLogger(RecordingRegisterService.class);
    // [해석] DB에는 KST로 저장한다(conventions.md '시각 형식'). 서버 시간대에 기대지 않으려고 명시(#16 ResultReceiveService와 같은 방식).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraQueryService cameraQueryService;
    private final RecordingRepository recordingRepository;
    private final RecordingPathValidator recordingPathValidator;

    public RecordingRegisterService(
            CameraQueryService cameraQueryService,
            RecordingRepository recordingRepository,
            RecordingPathValidator recordingPathValidator) {
        this.cameraQueryService = cameraQueryService;
        this.recordingRepository = recordingRepository;
        this.recordingPathValidator = recordingPathValidator;
    }

    @Transactional
    public RecordingRegisterResponse registerRecording(RecordingRegisterRequest request) {
        // [해석] ① 캠 확인. 빈 값이면 없는 캠(또는 삭제된 캠) → CameraNotFoundException → 404(FNC-A-026 예외 처리).
        //        #15가 만든 예외를 그대로 쓴다. 같은 '없는 캠'이라 같은 예외·같은 응답이어야 분석 서버가 한 가지로 처리할 수 있다.
        CameraSourceType sourceType = cameraQueryService
                .findSourceTypeOfExistingCamera(request.cameraId())
                .orElseThrow(() -> new CameraNotFoundException(request.cameraId()));
        // [해석] 시각을 먼저 KST·밀리초로 바꿔 둔다. 아래 비교(endedAt > startedAt)와 저장이 모두 같은 기준의 값을 쓰게 하려고.
        LocalDateTime startedAt = toKstMillis(request.startedAt());
        LocalDateTime endedAt = request.endedAt() == null ? null : toKstMillis(request.endedAt());
        // [해석] ② endedAt 규칙, ③ 경로 규칙. 어기면 RecordingRegisterException → 400. 저장 전이라 DB에는 아무것도 남지 않는다.
        validateEndedAt(sourceType, startedAt, endedAt);
        recordingPathValidator.validate(sourceType, request.videoPath(), request.boxPath());

        // [해석] ④ 저장(FNC-A-026 처리 순서 1). save가 돌려준 객체에 DB가 만든 id가 채워져 있다.
        Recording recording = recordingRepository.save(Recording.register(
                request.cameraId(), startedAt, endedAt, request.videoPath(), request.boxPath()));
        // 더미 캠은 반복마다 endedAt 없이 등록되므로 이번 시작 시각으로 이전 반복을 닫는다(FNC-A-026 처리 순서 2)
        // [해석] ⑤ 더미 캠은 영상이 끝나 처음부터 다시 시작할 때마다 새 녹화가 등록되는데, 분석 서버는 '이전 반복이 언제 끝났는지'를
        //        따로 보내지 않는다. 이전 반복은 이번 반복이 시작된 순간에 끝났으므로 이번 startedAt으로 닫는다.
        //        이렇게 해야 다시보기에서 "그 시각을 포함하는 녹화"를 찾을 때(started_at ≤ 시각 < ended_at) 반복마다 정확히 하나가 나온다.
        //        라이브는 등록할 때부터 endedAt이 있어 닫을 것이 없다.
        int closedCount = sourceType == CameraSourceType.DUMMY
                ? recordingRepository.closeOpenRecordingsStartedBefore(request.cameraId(), startedAt)
                : 0;
        // 캠마다 1분(라이브)·반복(더미)마다 오는 요청이라 DEBUG로만 남김(code-conventions 7-3)
        // [해석] 라이브 캠 1대면 하루 1,440건이라 INFO로 남기면 로그가 금방 쌓인다. 경로는 남기지 않고 id와 건수만 남긴다.
        log.debug(
                "녹화 등록 recordingId={} cameraId={} sourceType={} closed={}",
                recording.getId(),
                request.cameraId(),
                sourceType,
                closedCount);
        // [해석] ⑥ 응답은 엔티티가 아니라 DTO(id만). 컨트롤러가 201과 함께 내보낸다.
        return new RecordingRegisterResponse(recording.getId());
    }

    // 더미는 endedAt 없음, 라이브는 endedAt 필수(api/analyzer.md, FNC-A-026 업무 규칙)
    // [해석] 규칙 세 개(사용자와 합의한 결정 5):
    //        1. 더미 + endedAt 있음 → 400. 더미의 종료 시각은 다음 반복이 정하므로 분석 서버가 보내면 계약 위반이다.
    //        2. 라이브 + endedAt 없음 → 400. 라이브는 이전 녹화를 닫는 과정이 없어서, 없으면 영원히 '진행 중'으로 남는다.
    //        3. endedAt ≤ startedAt → 400(해석, '검토 필요'). 길이가 0이거나 음수인 녹화는 다시보기에서 찾을 수 없다.
    //        3번은 endedAt이 있을 때만 보므로 사실상 라이브에만 걸린다(더미는 1번에서 이미 걸러짐).
    private void validateEndedAt(CameraSourceType sourceType, LocalDateTime startedAt, LocalDateTime endedAt) {
        if (sourceType == CameraSourceType.DUMMY && endedAt != null) {
            throw new RecordingRegisterException("더미 캠 녹화는 endedAt 없이 등록해야 합니다.");
        }
        if (sourceType == CameraSourceType.LIVE && endedAt == null) {
            throw new RecordingRegisterException("라이브 캠 녹화는 endedAt이 필요합니다.");
        }
        if (endedAt != null && !endedAt.isAfter(startedAt)) {
            throw new RecordingRegisterException("endedAt은 startedAt보다 늦어야 합니다.");
        }
    }

    // DB에는 KST로 저장하고 started_at·ended_at은 밀리초까지 둔다(tables.md DATETIME(3))
    // [해석] 예: "2026-10-12T05:03:00.123456Z" → 2026-10-12 14:03:00.123. 마이크로초(456)를 버리는 이유: 칼럼이 밀리초까지라
    //        DB가 어차피 자르거나 반올림하는데, 반올림이면 저장된 값과 메모리의 값이 달라질 수 있다. 앱에서 먼저 잘라 두면 같다.
    private LocalDateTime toKstMillis(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }
}
