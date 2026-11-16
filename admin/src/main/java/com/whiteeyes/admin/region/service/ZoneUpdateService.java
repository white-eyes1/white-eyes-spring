package com.whiteeyes.admin.region.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

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

/** 구역의 이름과 다각형을 고친다(FNC-A-015). 다각형은 같은 동 안에서만 바꿀 수 있다. */
@Service
@RequiredArgsConstructor
public class ZoneUpdateService {

    // 저장하는 면적(㎡)의 소수 자릿수(DB 컬럼 기준)
    private static final int AREA_SCALE = 2;

    // 새 다각형의 검증·소속 동·면적·중심점 계산을 등록·미리보기와 같은 방식으로 재사용한다
    private final ZonePreviewService zonePreviewService;
    // 구역 조회·저장·이름 중복 확인
    private final ZoneRepository zoneRepository;
    // 관리자 작업 기록 저장과 로그인 계정 확인
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
        // 바꿀 것이 하나도 없는 요청은 의미가 없으므로 400으로 알린다
        if (name == null && boundary == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "name 또는 boundary 중 하나는 보내야 합니다.");
        }
        Zone zone = zoneRepository
                .findById(zoneId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
        // 작업 기록에 '이전' 값을 남기려고 수정 전에 보관한다(엔티티는 곧 바뀐다)
        String beforeName = zone.getName();
        BigDecimal beforeArea = zone.getAreaM2();

        // 처리 순서 1·2: 다각형이 바뀌면 서버가 면적·소속 동·중심점을 다시 계산하고, 소속 동이 바뀌면 거부한다
        ZoneCalculation calculation = boundary == null ? null : zonePreviewService.calculate(boundary);
        if (calculation != null && !calculation.dongId().equals(zone.getDongId())) {
            throw new DongChangeNotAllowedException(calculation.dongName());
        }
        // 처리 순서 3: 같은 동의 다른 구역과 이름 중복 확인 후 저장
        // 이름을 보내지 않았으면 기존 이름을 그대로 둔다
        String newName = name == null ? beforeName : name.trim();
        // 이름이 실제로 바뀔 때만 중복을 검사하고, 자기 자신(IdNot zoneId)은 비교에서 뺀다
        if (!newName.equals(beforeName)
                && zoneRepository.existsByDongIdAndNameAndDeletedAtIsNullAndIdNot(zone.getDongId(), newName, zoneId)) {
            throw new DuplicateZoneNameException(newName);
        }
        // 이름이 같아도 호출하며, 값이 같으면 변화가 없다
        zone.rename(newName);
        // 다각형을 보낸 경우에만 모양·면적·중심 좌표를 바꾼다. 소속 동은 위에서 같음을 확인했으므로 그대로다
        if (calculation != null) {
            zone.reshape(
                    BigDecimal.valueOf(calculation.areaM2()).setScale(AREA_SCALE, RoundingMode.HALF_UP),
                    boundary.toString(),
                    calculation.centerLat(),
                    calculation.centerLng());
        }
        // 즉시 DB에 반영해 제약 위반을 이 트랜잭션 안에서 바로 드러낸다
        zoneRepository.saveAndFlush(zone);

        // 처리 순서 4: 작업 기록(이전·이후 이름·면적). 같은 트랜잭션이라 기록이 실패하면 수정도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "UPDATE");
        detail.put("before", snapshot(beforeName, beforeArea));
        detail.put("after", snapshot(zone.getName(), zone.getAreaM2()));
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zone.getId(), zone.getName(), detail);
        return new ZoneUpdateResponse(zone.getId(), zone.getDongId(), zone.getName(), zone.getAreaM2());
    }

    /** 작업 기록에 남길 이름·면적 한 쌍을 순서가 유지되는 맵으로 만든다. */
    private Map<String, Object> snapshot(String name, BigDecimal areaM2) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("areaM2", areaM2);
        return snapshot;
    }
}
