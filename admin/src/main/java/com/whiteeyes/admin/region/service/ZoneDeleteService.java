package com.whiteeyes.admin.region.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/** 구역을 삭제한다(FNC-A-017). 가동 이력이 없으면 실제로 지우고, 있으면 삭제 표시만 한다. */
@Service
@RequiredArgsConstructor
public class ZoneDeleteService {

    // 삭제 시각(deletedAt)을 한국 시간으로 기록하려고 쓴다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 구역 저장소
    private final ZoneRepository zoneRepository;
    // 동서남북 이웃 행 저장소(구역을 지우면 이 구역이 들어간 행도 지워야 한다)
    private final ZoneNeighborRepository zoneNeighborRepository;
    // 구역 아래 캠의 상태 확인·삭제를 맡기는 캠 도메인의 공개 service
    private final ZoneCameraService zoneCameraService;
    // 아래 세 통계 저장소와 이벤트 저장소는 '이 구역에 쌓인 기록이 있는가'(이력 판정)에만 쓴다
    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final ZoneStatDailyRepository zoneStatDailyRepository;
    private final EventRepository eventRepository;
    // 관리자 작업 기록 저장과 로그인 계정 확인을 맡는다
    private final AdminWorkRecorder workRecorder;

    /**
     * @throws ZoneNotFoundException 없거나 이미 삭제된 구역이면(404)
     * @throws CameraRunningException 준비 중이거나 가동 중인 캠이 있으면(409)
     */
    @Transactional
    public ZoneDeleteResponse delete(Long zoneId) {
        // 로그인하지 않은 요청은 지우기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 없는 구역과 이미 삭제 표시된 구역은 똑같이 404로 다룬다(같은 구역을 두 번 지울 수 없게)
        Zone zone = zoneRepository
                .findById(zoneId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
        // 처리 순서 1: 소속 캠 중 PREPARING·RUNNING이 있으면 거부
        if (zoneCameraService.hasStartingOrRunningCamera(zoneId)) {
            throw new CameraRunningException(zoneId);
        }
        // 처리 순서 2: 이력 확인. 소속 캠(삭제된 캠 포함)이 모두 한 번도 가동한 적이 없고, 이 구역의 통계·이벤트 행도 없어야 이력 없음.
        // 시연용 통계는 캠을 가동하지 않은 구역에도 만들어지고, 통계·이벤트는 구역을 외래키로 가리켜 실제 삭제하면 500이 난다
        boolean hasHistory = zoneCameraService.hasStartHistory(zoneId) || hasStatOrEvent(zoneId);
        // 작업 기록에 남길 영향받은 캠 수. 분기마다 다른 방법으로 채운다
        int cameraCount;
        if (hasHistory) {
            // 처리 순서 4: 구역·소속 캠에 삭제 표시를 하고, 이 구역이 들어간 이웃 행은 모두 지운다
            LocalDateTime now = LocalDateTime.now(KST);
            cameraCount = zoneCameraService.markAllCamerasDeleted(zoneId, now);
            zoneNeighborRepository.deleteAllInvolving(zoneId);
            zone.markDeleted(now);
            // 삭제 표시를 바로 DB에 반영한다
            zoneRepository.saveAndFlush(zone);
        } else {
            // 처리 순서 3: 이웃 행·캠·구역을 실제로 지운다
            zoneNeighborRepository.deleteAllInvolving(zoneId);
            cameraCount = zoneCameraService.deleteAllCameras(zoneId);
            // 캠을 먼저 지운 뒤 구역을 지운다(캠이 구역을 외래키로 가리키기 때문)
            zoneRepository.delete(zone);
            // 외래키 위반 같은 문제를 이 트랜잭션 안에서 바로 드러내려고 즉시 반영한다
            zoneRepository.flush();
        }
        // 응답과 작업 기록에 같은 값을 쓰도록 한 번만 정한다
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

    /** 1분·시간별·일별 통계나 이벤트가 하나라도 이 구역을 가리키면 true. 이런 행이 있으면 실제 삭제를 할 수 없다. */
    private boolean hasStatOrEvent(Long zoneId) {
        return zoneStatMinuteRepository.existsByZoneId(zoneId)
                || zoneStatHourlyRepository.existsByZoneId(zoneId)
                || zoneStatDailyRepository.existsByZoneId(zoneId)
                || eventRepository.existsByZoneId(zoneId);
    }
}
