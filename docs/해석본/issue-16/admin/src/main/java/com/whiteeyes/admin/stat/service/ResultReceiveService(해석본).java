/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/ResultReceiveService.java
 * 관련 이슈: #16(FNC-A-024 분석 결과 수신)
 *
 * [무엇] FNC-A-024의 처리 순서 1~4를 실제로 수행하는 업무 로직. 컨트롤러가 검증을 끝낸 결과 묶음을 받아
 *   ① 받을 캠인지 확인 → ② 마감된 분인지 확인 → ③ 누적기에 넣기 → ④ 마지막 결과 시각 갱신 을 한다.
 * [왜 이렇게 나눴나] package-structure 3-1: controller는 요청 받기·형식 검증, service는 업무 판단.
 *   그래서 '버릴지 말지' 같은 판단은 전부 여기 있고, 컨트롤러는 이 메서드 하나만 부른다.
 * [염두에 둔 것]
 *   - 다른 도메인(camera)은 그 도메인의 service(CameraQueryService)로만 부른다. CameraRepository를 직접 쓰지 않는다(3-2).
 *   - 묶음 안의 항목 하나가 버려져도 나머지는 정상 처리한다. 버리는 것은 '오류'가 아니라 명세에 정해진 정상 흐름이라
 *     예외를 던지지 않고 건수만 센다.
 *   - @Transactional을 붙이지 않았다. 이 메서드는 DB에 쓰지 않고(메모리에만 모음), DB 읽기는 CameraQueryService가
 *     자기 트랜잭션(readOnly)으로 처리한다. 쓸데없이 트랜잭션을 길게 잡지 않으려고.
 */
package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.whiteeyes.admin.camera.service.CameraQueryService;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest.DirectionCount;

@Service
public class ResultReceiveService {

    // [해석] 로거는 SLF4J API로 쓴다(code-conventions 7-1). 실제 출력은 Log4j2가 하지만 코드에서 Log4j2 클래스를 직접 쓰지 않는다.
    private static final Logger log = LoggerFactory.getLogger(ResultReceiveService.class);
    // [해석] DB에는 KST로 저장한다(conventions.md '시각 형식', tables.md stat_at "KST"). 그래서 받은 시각을 KST로 바꿀 기준.
    //        서버(VM)의 기본 시간대에 기대지 않으려고 "Asia/Seoul"을 명시했다. 서버 시간대가 UTC여도 결과가 같다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraQueryService cameraQueryService;
    private final MinuteAccumulatorStore minuteAccumulatorStore;
    private final LastResultTimeStore lastResultTimeStore;

    public ResultReceiveService(
            CameraQueryService cameraQueryService,
            MinuteAccumulatorStore minuteAccumulatorStore,
            LastResultTimeStore lastResultTimeStore) {
        this.cameraQueryService = cameraQueryService;
        this.minuteAccumulatorStore = minuteAccumulatorStore;
        this.lastResultTimeStore = lastResultTimeStore;
    }

    // [해석] 반환값이 없다(void). API는 204(본문 없음)만 돌려주고, 분석 서버는 개별 항목이 버려졌는지 알 필요가 없다
    //        (버리는 판단은 관리자 앱의 일이고, 분석 서버가 다시 보내도 결과는 같다).
    public void receiveResults(List<ResultReceiveRequest> results) {
        // 항목마다 조회하지 않고 묶음 안의 캠을 한 번에 조회(FNC-A-024 처리 순서 1)
        // [해석] 최대 2,000건을 항목마다 조회하면 1초에 쿼리 2,000번이 된다. 캠 id만 모아(Set으로 중복 제거)
        //        IN 조회 한 번으로 끝낸다. 명세의 "(캐시)"가 노린 '조회 부담 줄이기'를 캐시 없이 달성하는 방법이다.
        Set<Long> cameraIds =
                results.stream().map(ResultReceiveRequest::cameraId).collect(Collectors.toSet());
        Map<Long, Long> zoneIdByCamera = cameraQueryService.findZoneIdsOfReceivingCameras(cameraIds);

        int droppedCount = 0;
        for (ResultReceiveRequest result : results) {
            // [해석] 맵에 없으면 null = 받지 않을 캠(삭제, run_requested=0, 존재하지 않음).
            Long zoneId = zoneIdByCamera.get(result.cameraId());
            LocalDateTime second = toKstSecond(result.at());
            // 삭제·정지·없는 캠이거나 이미 마감된 분이면 버림(FNC-A-024 처리 순서 1·2)
            // [해석] || 는 왼쪽이 참이면 오른쪽을 실행하지 않는다. 그래서 받지 않을 캠은 누적기에 아예 닿지 않는다.
            //        add가 false면 '마감된 분이라 버림'. 두 경우 모두 같은 처리(건수만 세고 다음 항목)라 한 조건으로 묶었다.
            if (zoneId == null || !minuteAccumulatorStore.add(zoneId, second, toSecondResult(result))) {
                droppedCount++;
                continue;
            }
            // [해석] FNC-A-024 처리 순서 4. 실제로 누적기에 들어간 결과만 반영한다(버린 결과는 '살아 있다'는 증거가 아니므로).
            //        같은 묶음에 재전송된 옛 결과가 섞여 있어도 저장소가 '더 늦은 시각'만 남기므로 순서와 상관없다.
            lastResultTimeStore.update(result.cameraId(), second);
        }
        // 1초마다 오는 요청이라 DEBUG로만 남김(code-conventions 7-3)
        // [해석] INFO로 남기면 하루 86,400줄이 쌓인다. 파라미터 방식({})으로 써서 DEBUG가 꺼져 있으면 문자열을 만들지도 않는다.
        log.debug("분석 결과 수신 received={} dropped={}", results.size(), droppedCount);
    }

    // [해석] 예: "2026-10-12T05:03:05.500Z"(UTC) → 2026-10-12 14:03:05(KST, 오프셋 없음).
    //        atZoneSameInstant: 같은 '순간'을 KST 시계로 다시 읽는다(시각 숫자만 바꿔 붙이는 것이 아님).
    //        truncatedTo(SECONDS): 초 아래를 버린다. 계약상 at은 '1초 구간의 시작'(FNC-S-006)이라 원래 초 단위지만,
    //        혹시 밀리초가 붙어 와도 같은 초는 같은 키가 되도록(중복 판정이 어긋나지 않도록) 한 번 더 맞춘다.
    private LocalDateTime toKstSecond(OffsetDateTime at) {
        return at.atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
    }

    // [해석] 요청 DTO → 서비스 내부 값. DTO를 누적기까지 들고 가지 않게 여기서 한 번 바꾼다(변환은 service에서, 3-3).
    private SecondResult toSecondResult(ResultReceiveRequest result) {
        return new SecondResult(
                result.personCount(),
                toDirectionCounts(result.in()),
                toDirectionCounts(result.out()),
                result.dwellSec());
    }

    // [해석] DTO의 DirectionCount(Integer, JSON 키 매핑) → 내부 값 DirectionCounts(int). 검증을 통과했으므로 null이 없다.
    private DirectionCounts toDirectionCounts(DirectionCount count) {
        return new DirectionCounts(count.north(), count.east(), count.south(), count.west());
    }
}
