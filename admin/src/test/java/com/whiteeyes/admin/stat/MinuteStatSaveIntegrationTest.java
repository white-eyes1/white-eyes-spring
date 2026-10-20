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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;

// INSERT … ON DUPLICATE KEY UPDATE와 수신 → 마감 → 저장 흐름이 실제 DB에서 맞게 동작하는지 H2로 확인한다.
// 앱의 스케줄러는 실제 현재 시각으로 돌기 때문에, 테스트 시각은 스케줄러가 마감하지 않을 먼 미래(2099년)로 둔다
/** 1분 통계 저장(FNC-A-028) */
@SpringBootTest
@Transactional
class MinuteStatSaveIntegrationTest {

    private static final LocalDateTime MINUTE = LocalDateTime.of(2099, 1, 1, 10, 0);
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        assertThat(zoneStatMinuteRepository.findAll())
                .extracting(ZoneStatMinute::getStatAt, ZoneStatMinute::getSampleSec)
                .containsExactlyInAnyOrder(tuple(MINUTE, (short) 60), tuple(MINUTE.plusMinutes(1), (short) 60));
        ZoneStatMinute first =
                zoneStatMinuteRepository.findById(new ZoneStatMinuteId(zoneId, MINUTE)).orElseThrow();
        // 10명 ÷ 50㎡
        assertThat(first.getDensity()).isEqualByComparingTo("0.2000");
    }

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

    // zone → region 외래키가 있어 테스트용 동을 먼저 넣는다. 테스트가 끝나면 롤백된다
    private Long saveZone(String areaM2) {
        jdbcTemplate.update("INSERT INTO region (level, code, name, boundary, center_lat, center_lng)"
                + " VALUES ('EMD', '99999991', '통계 테스트동', '{}', 37.600000, 127.002000)");
        Long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE code = '99999991'", Long.class);
        Zone zone = new Zone(dongId, "통계 1구역", new BigDecimal(areaM2), "{}", new BigDecimal("37.600000"),
                new BigDecimal("127.002000"));
        return zoneRepository.save(zone).getId();
    }

    // 가동 상태는 생성자로 정할 수 없어 직접 넣는다
    private Long saveRunningCamera(Long zoneId) {
        Camera camera = new Camera(zoneId, "통계 캠", CameraSourceType.DUMMY, "dummy/videos/a.mp4");
        ReflectionTestUtils.setField(camera, "status", CameraStatus.RUNNING);
        ReflectionTestUtils.setField(camera, "runRequested", true);
        return cameraRepository.save(camera).getId();
    }
}
