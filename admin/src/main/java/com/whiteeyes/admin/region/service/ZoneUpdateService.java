package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.dto.ZoneCalculation;
import com.whiteeyes.admin.region.dto.ZoneUpdateResponse;
import com.whiteeyes.admin.region.exception.DongChangeNotAllowedException;
import com.whiteeyes.admin.region.exception.DuplicateZoneNameException;
import com.whiteeyes.admin.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.security.LoginAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 구역의 이름과 다각형을 고친다(FNC-A-015). 다각형은 같은 동 안에서만 바꿀 수 있다. */
@Service
@RequiredArgsConstructor
public class ZoneUpdateService {

    private static final int AREA_SCALE = 2;

    private final ZonePreviewService zonePreviewService;
    private final ZoneRepository zoneRepository;
    private final AdminWorkRecorder workRecorder;

    /**
     * @param name 새 이름(보내지 않으면 null)
     * @param boundary 새 다각형(보내지 않으면 null)
     * @throws ZoneNotFoundException 없거나 삭제된 구역이면(404)
     * @throws DongChangeNotAllowedException 다른 동으로 바뀌면(400)
     * @throws DuplicateZoneNameException 같은 동에 같은 이름이 있으면(409)
     */
    @Transactional
    public ZoneUpdateResponse update(Long zoneId, String name, JsonNode boundary) {
        // 로그인하지 않은 요청은 구역을 바꾸기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        if (name == null && boundary == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "name 또는 boundary 중 하나는 보내야 합니다.");
        }
        Zone zone = zoneRepository.findById(zoneId).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
        String beforeName = zone.getName();
        BigDecimal beforeArea = zone.getAreaM2();

        // 처리 순서 1·2: 다각형이 바뀌면 서버가 면적·소속 동·중심점을 다시 계산하고, 소속 동이 바뀌면 거부한다
        ZoneCalculation calculation = boundary == null ? null : zonePreviewService.calculate(boundary);
        if (calculation != null && !calculation.dongId().equals(zone.getDongId())) {
            throw new DongChangeNotAllowedException(calculation.dongName());
        }
        // 처리 순서 3: 같은 동의 다른 구역과 이름 중복 확인 후 저장
        String newName = name == null ? beforeName : name.trim();
        if (!newName.equals(beforeName)
                && zoneRepository.existsByDongIdAndNameAndDeletedAtIsNullAndIdNot(zone.getDongId(), newName, zoneId)) {
            throw new DuplicateZoneNameException(newName);
        }
        zone.rename(newName);
        if (calculation != null) {
            zone.reshape(
                    BigDecimal.valueOf(calculation.areaM2()).setScale(AREA_SCALE, RoundingMode.HALF_UP),
                    boundary.toString(),
                    calculation.centerLat(),
                    calculation.centerLng());
        }
        zoneRepository.saveAndFlush(zone);

        // 처리 순서 4: 작업 기록(이전·이후 이름·면적). 같은 트랜잭션이라 기록이 실패하면 수정도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "UPDATE");
        detail.put("before", snapshot(beforeName, beforeArea));
        detail.put("after", snapshot(zone.getName(), zone.getAreaM2()));
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zone.getId(), zone.getName(), detail);
        return new ZoneUpdateResponse(zone.getId(), zone.getDongId(), zone.getName(), zone.getAreaM2());
    }

    private Map<String, Object> snapshot(String name, BigDecimal areaM2) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("areaM2", areaM2);
        return snapshot;
    }
}
