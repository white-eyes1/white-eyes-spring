package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.dto.ZoneDeleteResponse;
import com.whiteeyes.admin.region.exception.CameraRunningException;
import com.whiteeyes.admin.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.camera.service.ZoneCameraService;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.security.LoginAccount;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구역을 삭제한다(FNC-A-017). 가동 이력이 없으면 실제로 지우고, 있으면 삭제 표시만 한다. */
@Service
@RequiredArgsConstructor
public class ZoneDeleteService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ZoneRepository zoneRepository;
    private final ZoneNeighborRepository zoneNeighborRepository;
    private final ZoneCameraService zoneCameraService;
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final ZoneStatDailyRepository zoneStatDailyRepository;
    private final EventRepository eventRepository;
    private final AdminWorkRecorder workRecorder;

    /**
     * @throws ZoneNotFoundException 없거나 이미 삭제된 구역이면(404)
     * @throws CameraRunningException 준비 중이거나 가동 중인 캠이 있으면(409)
     */
    @Transactional
    public ZoneDeleteResponse delete(Long zoneId) {
        // 로그인하지 않은 요청은 지우기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        Zone zone = zoneRepository.findById(zoneId).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
        // 처리 순서 1: 소속 캠 중 PREPARING·RUNNING이 있으면 거부
        if (zoneCameraService.hasStartingOrRunningCamera(zoneId)) {
            throw new CameraRunningException(zoneId);
        }
        // 처리 순서 2: 이력 확인. 소속 캠(삭제된 캠 포함)이 모두 한 번도 가동한 적이 없고, 이 구역의 통계·이벤트 행도 없어야 이력 없음.
        // 시연용 통계는 캠을 가동하지 않은 구역에도 만들어지고, 통계·이벤트는 구역을 외래키로 가리켜 실제 삭제하면 500이 난다
        boolean hasHistory = zoneCameraService.hasStartHistory(zoneId) || hasStatOrEvent(zoneId);
        int cameraCount;
        if (hasHistory) {
            // 처리 순서 4: 구역·소속 캠에 삭제 표시를 하고, 이 구역이 들어간 이웃 행은 모두 지운다
            LocalDateTime now = LocalDateTime.now(KST);
            cameraCount = zoneCameraService.markAllCamerasDeleted(zoneId, now);
            zoneNeighborRepository.deleteAllInvolving(zoneId);
            zone.markDeleted(now);
            zoneRepository.saveAndFlush(zone);
        } else {
            // 처리 순서 3: 이웃 행·캠·구역을 실제로 지운다
            zoneNeighborRepository.deleteAllInvolving(zoneId);
            cameraCount = zoneCameraService.deleteAllCameras(zoneId);
            zoneRepository.delete(zone);
            zoneRepository.flush();
        }
        String mode = hasHistory ? "SOFT" : "HARD";
        // 처리 순서 5: 작업 기록(op: DELETE, hard/soft). 같은 트랜잭션이라 기록이 실패하면 삭제도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "DELETE");
        detail.put("mode", mode);
        detail.put("name", zone.getName());
        detail.put("cameraCount", cameraCount);
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zoneId, zone.getName(), detail);
        return new ZoneDeleteResponse(mode);
    }

    private boolean hasStatOrEvent(Long zoneId) {
        return zoneStatMinuteRepository.existsByZoneId(zoneId)
                || zoneStatHourlyRepository.existsByZoneId(zoneId)
                || zoneStatDailyRepository.existsByZoneId(zoneId)
                || eventRepository.existsByZoneId(zoneId);
    }
}
