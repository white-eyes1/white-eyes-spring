package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.dto.ZoneCalculation;
import com.whiteeyes.admin.region.dto.ZoneCreateResponse;
import com.whiteeyes.admin.region.exception.DuplicateZoneNameException;
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

/** 지도에 그린 다각형으로 구역을 만든다(FNC-A-014). */
@Service
@RequiredArgsConstructor
public class ZoneRegisterService {

    private static final int AREA_SCALE = 2;

    private final ZonePreviewService zonePreviewService;
    private final ZoneRepository zoneRepository;
    private final AdminWorkRecorder workRecorder;

    @Transactional
    public ZoneCreateResponse register(String name, JsonNode boundary) {
        // 로그인하지 않은 요청은 구역을 만들기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        String trimmedName = name.trim();
        // 처리 순서 1: 화면이 보낸 값이 아니라 서버가 다시 계산한 값을 쓴다(검증 오류도 여기서 난다)
        ZoneCalculation calculation = zonePreviewService.calculate(boundary);
        // 처리 순서 2: 같은 동의 삭제되지 않은 구역과 이름 중복 확인
        if (zoneRepository.existsByDongIdAndNameAndDeletedAtIsNull(calculation.dongId(), trimmedName)) {
            throw new DuplicateZoneNameException(trimmedName);
        }
        // 처리 순서 3·4: 중심점은 계산 때 구한 다각형 안의 점. zone 저장
        Zone zone = zoneRepository.save(new Zone(
                calculation.dongId(),
                trimmedName,
                BigDecimal.valueOf(calculation.areaM2()).setScale(AREA_SCALE, RoundingMode.HALF_UP),
                boundary.toString(),
                calculation.centerLat(),
                calculation.centerLng()));
        // 처리 순서 5: 작업 기록(ZONE_CHANGE). 같은 트랜잭션이라 기록이 실패하면 구역 등록도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "CREATE");
        detail.put("name", zone.getName());
        detail.put("areaM2", zone.getAreaM2());
        detail.put("dongId", zone.getDongId());
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zone.getId(), zone.getName(), detail);
        return new ZoneCreateResponse(zone.getId(), zone.getDongId(), zone.getName(), calculation.areaM2());
    }
}
