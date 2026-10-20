/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/stat/MinuteStatSaveIntegrationTest.java
 * 관련 이슈: #18(FNC-A-028), 함께 확인하는 기준: FNC-S-006 "관리자 앱을 2분 껐다 켜면 그 사이 1분 통계가 채워짐"
 *
 * [무엇] 가짜(mock) 없이 앱 전체를 띄우고 테스트용 H2 DB에 실제로 저장해 보는 통합 테스트.
 * [왜 필요한가] 단위 테스트는 리포지토리를 가짜로 바꿔서, 네이티브 SQL(INSERT … ON DUPLICATE KEY UPDATE)이
 *   실제 DB에서 도는지는 확인하지 못한다. 사용자와 합의한 결정 4에서 "H2에서 실제로 동작하는지 통합 테스트로 확인하고,
 *   안 되면 멈추고 묻는다"고 했고, 이 테스트가 그 확인이다(통과함).
 * [두 번째 테스트] #16(수신) → #18(마감·저장)이 이어지는 실제 흐름을 한 번에 확인한다. 관리자 앱이 2분 꺼져 있다가 켜져
 *   분석 서버가 그 2분(120초) 결과를 한 묶음으로 다시 보낸 상황이다.
 */
package com.whiteeyes.admin.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.stat.dto.ResultReceiveRequest;
import com.whiteeyes.admin.stat.dto.ResultReceiveRequest.DirectionCount;
import com.whiteeyes.admin.stat.service.MinuteStatService;
import com.whiteeyes.admin.stat.service.ResultReceiveService;
import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import com.whiteeyes.common.camera.entity.CameraStatus;
import com.whiteeyes.common.camera.repository.CameraRepository;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.entity.ZoneStatMinuteId;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.zone.entity.Zone;
import com.whiteeyes.common.zone.repository.ZoneRepository;

// INSERT … ON DUPLICATE KEY UPDATE와 수신 → 마감 → 저장 흐름이 실제 DB에서 맞게 동작하는지 H2로 확인한다.
// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 시각은 스케줄러가 마감하지 않을 먼 미래(2099년)로 둔다
// [해석] @SpringBootTest로 앱을 띄우면 SchedulingConfig 때문에 진짜 스케줄러도 매초 돈다. 테스트 데이터를 지금 시각
//        근처(예: 2026년)로 넣으면, 테스트가 closeEndedMinutes를 부르기 전에 스케줄러가 먼저 마감해 버려 결과가 그때그때 달라진다.
//        스케줄러를 끄는 설정을 따로 만드는 대신, 스케줄러의 '지금'으로는 절대 마감되지 않을 2099년을 쓰고
//        테스트가 원하는 '지금'(2099년 10:02:05)을 직접 넘긴다.
//        @Transactional: 테스트가 끝나면 넣은 데이터를 모두 되돌려(롤백) 다른 테스트에 영향을 주지 않는다.
@SpringBootTest
@Transactional
class MinuteStatSaveIntegrationTest {

    private static final LocalDateTime MINUTE = LocalDateTime.of(2099, 1, 1, 10, 0);
    // [해석] 분석 서버는 시각을 +09:00 오프셋을 붙여 보낸다(conventions.md 시각 형식). 실제 요청과 같은 모양으로 만든다.
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    @Autowired
    private ZoneStatMinuteRepository zoneStatMinuteRepository;

    @Autowired
    private ResultReceiveService resultReceiveService;

    @Autowired
    private MinuteStatService minuteStatService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private CameraRepository cameraRepository;

    // [해석] 결정 4의 확인. 같은 (구역, 10:00)을 두 번 upsert하면 중복 키 오류 없이 행이 1개만 남고,
    //        값은 두 번째 것(20.50, 60초)으로 바뀌어야 한다.
    @Test
    @DisplayName("FNC-A-028 같은 (구역, 분)을 다시 저장하면 행 하나를 덮어씀")
    void upsertOverwritesSameZoneAndMinute() {
        Long zoneId = saveZone("100.00");

        zoneStatMinuteRepository.upsert(stat(zoneId, "10.00", (short) 30));
        zoneStatMinuteRepository.upsert(stat(zoneId, "20.50", (short) 60));

        assertThat(zoneStatMinuteRepository.count()).isEqualTo(1);
        ZoneStatMinute saved =
                zoneStatMinuteRepository.findById(new ZoneStatMinuteId(zoneId, MINUTE)).orElseThrow();
        assertThat(saved.getCountAvg()).isEqualByComparingTo("20.50");
        assertThat(saved.getSampleSec()).isEqualTo((short) 60);
    }

    // [해석] 재시작 시나리오. 순서:
    //        1. 구역(50㎡)과 가동 중인 캠을 DB에 넣는다(#16 수신이 캠을 조회해 구역을 찾기 때문).
    //        2. 10:00:00~10:01:59의 120초 결과를 한 묶음으로 받는다(재시작 직후 재전송).
    //           새로 뜬 앱이라 마감 기록이 없으므로 #16이 지난 분이어도 받아 준다.
    //        3. 지금 = 10:02:05로 마감을 부른다 → 10:00과 10:01 구간이 모두 '분 끝 + 5초'를 지났으므로 둘 다 저장.
    //        확인: 행 2개, 각각 60초를 다 받았는지(sample_sec = 60), 밀집도 계산(10명 ÷ 50㎡ = 0.2)이 맞는지.
    //        PR에 적은 '알려진 한계'(2,000건을 넘으면 묶음 사이에 마감됨)는 이 테스트 범위 밖이다. 120건은 한 묶음이다.
    @Test
    @DisplayName("FNC-A-028 관리자 앱 재시작 뒤 한 묶음으로 재전송된 지난 2분의 결과가 1분 통계 2행으로 저장됨(FNC-S-006)")
    void savesPastMinutesResentAfterRestart() {
        Long zoneId = saveZone("50.00");
        Long cameraId = saveRunningCamera(zoneId);
        List<ResultReceiveRequest> resent = new ArrayList<>();
        for (int second = 0; second < 120; second++) {
            resent.add(result(cameraId, MINUTE.plusSeconds(second), 10));
        }

        resultReceiveService.receiveResults(resent);
        minuteStatService.closeEndedMinutes(MINUTE.plusMinutes(2).plusSeconds(5));

        // [해석] extracting으로 (시각, 받은 초 수) 쌍만 뽑아 비교한다. findAll은 순서를 보장하지 않으므로 InAnyOrder.
        assertThat(zoneStatMinuteRepository.findAll())
                .extracting(ZoneStatMinute::getStatAt, ZoneStatMinute::getSampleSec)
                .containsExactlyInAnyOrder(tuple(MINUTE, (short) 60), tuple(MINUTE.plusMinutes(1), (short) 60));
        ZoneStatMinute first =
                zoneStatMinuteRepository.findById(new ZoneStatMinuteId(zoneId, MINUTE)).orElseThrow();
        // 10명 ÷ 50㎡
        assertThat(first.getDensity()).isEqualByComparingTo("0.2000");
    }

    // [해석] upsert 테스트용 행. 확인하지 않는 칸(유입·유출·체류)은 빌더에서 생략해 기본값 0이 들어간다.
    private ZoneStatMinute stat(Long zoneId, String countAvg, short sampleSec) {
        return ZoneStatMinute.builder()
                .zoneId(zoneId)
                .statAt(MINUTE)
                .countAvg(new BigDecimal(countAvg))
                .countMax(25)
                .countMin(5)
                .density(new BigDecimal("0.1000"))
                .sampleSec(sampleSec)
                .build();
    }

    private ResultReceiveRequest result(Long cameraId, LocalDateTime at, int personCount) {
        DirectionCount none = new DirectionCount(0, 0, 0, 0);
        return new ResultReceiveRequest(
                cameraId, OffsetDateTime.of(at, KST_OFFSET), personCount, none, none, List.of());
    }

    // TODO(#10): Zone에 생성 메서드가 생기면 그것으로 바꾼다
    // [해석] id는 넣지 않는다. 저장할 때 DB가 AUTO_INCREMENT로 정해 주고, 그 id를 캠의 zoneId로 쓴다.
    private Long saveZone(String areaM2) {
        Zone zone = BeanUtils.instantiateClass(Zone.class);
        ReflectionTestUtils.setField(zone, "areaM2", new BigDecimal(areaM2));
        return zoneRepository.save(zone).getId();
    }

    // TODO(#11): Camera에 생성 메서드가 생기면 그것으로 바꾼다
    // [해석] #16 수신은 '삭제되지 않았고 run_requested=1'인 캠의 결과만 받는다. 그래서 runRequested=true로 넣는다.
    private Long saveRunningCamera(Long zoneId) {
        Camera camera = BeanUtils.instantiateClass(Camera.class);
        ReflectionTestUtils.setField(camera, "zoneId", zoneId);
        ReflectionTestUtils.setField(camera, "sourceType", CameraSourceType.DUMMY);
        ReflectionTestUtils.setField(camera, "status", CameraStatus.RUNNING);
        ReflectionTestUtils.setField(camera, "runRequested", true);
        return cameraRepository.save(camera).getId();
    }
}
