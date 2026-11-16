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
import com.whiteeyes.admin.region.dto.ZoneCreateResponse;
import com.whiteeyes.admin.region.exception.DuplicateZoneNameException;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.security.LoginAccount;

/** 지도에 그린 다각형으로 구역을 만든다(FNC-A-014). */
@Service
@RequiredArgsConstructor
public class ZoneRegisterService {

    // 저장하는 면적(㎡)의 소수 자릿수(DB 컬럼 기준)
    private static final int AREA_SCALE = 2;

    // 미리보기와 같은 계산(검증·소속 동·면적·중심점)을 다시 쓴다
    private final ZonePreviewService zonePreviewService;
    // 구역 저장·이름 중복 확인
    private final ZoneRepository zoneRepository;
    // 관리자 작업 기록 저장과 로그인 계정 확인
    private final AdminWorkRecorder workRecorder;

    @Transactional
    /**
     * 구역을 새로 만들어 저장하고 작업 기록을 남긴다.
     *
     * @param name 구역 이름(앞뒤 공백은 여기서 제거)
     * @param boundary GeoJSON Polygon
     * @throws DuplicateZoneNameException 같은 동에 같은 이름의 구역이 있을 때(409)
     */
    public ZoneCreateResponse register(String name, JsonNode boundary) {
        // 로그인하지 않은 요청은 구역을 만들기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 공백만 다른 이름이 중복 검사를 피하지 못하도록 저장 전에 다듬는다
        String trimmedName = name.trim();
        // 처리 순서 1: 화면이 보낸 값이 아니라 서버가 다시 계산한 값을 쓴다(검증 오류도 여기서 난다)
        ZoneCalculation calculation = zonePreviewService.calculate(boundary);
        // 처리 순서 2: 같은 동의 삭제되지 않은 구역과 이름 중복 확인
        if (zoneRepository.existsByDongIdAndNameAndDeletedAtIsNull(calculation.dongId(), trimmedName)) {
            throw new DuplicateZoneNameException(trimmedName);
        }
        // 처리 순서 3·4: 중심점은 계산 때 구한 다각형 안의 점. zone 저장
        // 경계는 요청 JSON을 문자열 그대로 저장한다. 면적·중심 좌표·소속 동은 서버 계산값이다
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
        // 응답의 면적(double)과 달리, 기록에는 실제 저장된 값(소수 2자리)을 남긴다
        detail.put("areaM2", zone.getAreaM2());
        detail.put("dongId", zone.getDongId());
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zone.getId(), zone.getName(), detail);
        // 새 구역 번호는 save가 채워 준 값이다
        return new ZoneCreateResponse(zone.getId(), zone.getDongId(), zone.getName(), calculation.areaM2());
    }
}
