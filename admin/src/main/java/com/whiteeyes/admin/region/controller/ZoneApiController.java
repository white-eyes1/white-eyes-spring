package com.whiteeyes.admin.region.controller;

import java.util.EnumMap;
import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.whiteeyes.admin.region.dto.ZoneCreateRequest;
import com.whiteeyes.admin.region.dto.ZoneCreateResponse;
import com.whiteeyes.admin.region.dto.ZoneDeleteResponse;
import com.whiteeyes.admin.region.dto.ZoneNeighborRef;
import com.whiteeyes.admin.region.dto.ZonePreviewRequest;
import com.whiteeyes.admin.region.dto.ZonePreviewResponse;
import com.whiteeyes.admin.region.dto.ZoneUpdateRequest;
import com.whiteeyes.admin.region.dto.ZoneUpdateResponse;
import com.whiteeyes.admin.region.exception.InvalidZoneNeighborException;
import com.whiteeyes.admin.region.service.ZoneDeleteService;
import com.whiteeyes.admin.region.service.ZoneNeighborService;
import com.whiteeyes.admin.region.service.ZonePreviewService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.region.service.ZoneUpdateService;
import com.whiteeyes.common.region.entity.ZoneDirection;

/** 구역 관리 API(FNC-A-013~017): 미리보기 계산, 등록, 수정, 이웃 설정, 삭제. */
@RestController
@RequestMapping("/api/v1/manage/zones")
@RequiredArgsConstructor
public class ZoneApiController {

    // 기능별 service. 컨트롤러는 요청 형식 검증(@Valid)과 service 위임만 하고, 계산·권한·기록은 service가 맡는다
    private final ZoneRegisterService zoneRegisterService;
    private final ZoneUpdateService zoneUpdateService;
    private final ZoneDeleteService zoneDeleteService;
    private final ZoneNeighborService zoneNeighborService;
    private final ZonePreviewService zonePreviewService;

    /** FNC-A-013 구역 미리보기 계산: 저장 전에 면적·소속 동을 계산한다. */
    @PostMapping("/preview")
    public ZonePreviewResponse preview(@Valid @RequestBody ZonePreviewRequest request) {
        // 저장하지 않고 면적·소속 동만 계산한다(DB 변경 없음). 잘못된 도형은 service가 예외로 알린다
        return zonePreviewService.preview(request.boundary());
    }

    /** FNC-A-014 구역 등록 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneCreateResponse register(@Valid @RequestBody ZoneCreateRequest request) {
        // 이름 앞뒤 공백 정리와 면적·소속 동 재계산은 service가 한다. 성공하면 위 @ResponseStatus에 따라 201
        return zoneRegisterService.register(request.name(), request.boundary());
    }

    /** FNC-A-015 구역 수정: 보낸 필드(이름·다각형)만 고친다. 다각형은 같은 동 안에서만 바꿀 수 있다. */
    @PatchMapping("/{id}")
    public ZoneUpdateResponse update(@PathVariable("id") Long id, @Valid @RequestBody ZoneUpdateRequest request) {
        // PATCH라 name·boundary는 보낸 것만 반영한다(둘 다 없으면 service가 400). 경로의 id가 대상 구역
        return zoneUpdateService.update(id, request.name(), request.boundary());
    }

    /** FNC-A-017 구역 삭제: 가동 이력이 없으면 실제로 지우고(HARD), 있으면 삭제 표시만 한다(SOFT). */
    @DeleteMapping("/{id}")
    public ZoneDeleteResponse delete(@PathVariable("id") Long id) {
        // 가동 이력이 있으면 삭제 표시(SOFT), 없으면 실제 삭제(HARD). 어느 쪽이었는지 mode로 돌려준다
        return zoneDeleteService.delete(id);
    }

    /** FNC-A-016 구역 이웃 조회: 4방향 이웃(없는 방향은 null). */
    @GetMapping("/{id}/neighbors")
    public Map<String, ZoneNeighborRef> findNeighbors(@PathVariable("id") Long id) {
        // 키는 N·E·S·W 고정, 값은 이웃 구역 요약(이웃이 없는 방향은 null)
        return zoneNeighborService.find(id);
    }

    /** FNC-A-016 구역 이웃 저장: 4방향 이웃을 저장하고 반대쪽 구역의 반대 방향도 맞춘다. */
    @PutMapping("/{id}/neighbors")
    public Map<String, ZoneNeighborRef> saveNeighbors(
            @PathVariable("id") Long id, @RequestBody Map<String, Long> body) {
        // 요청 본문의 문자열 키를 먼저 ZoneDirection으로 바꾼 뒤 저장하고, 저장 후의 이웃 상태를 그대로 돌려준다
        return zoneNeighborService.save(id, toDirections(body));
    }

    // 키는 N·E·S·W만 받는다(그 밖의 키는 400). 빠진 방향은 이웃 없음으로 본다
    private Map<ZoneDirection, Long> toDirections(Map<String, Long> body) {
        // EnumMap은 enum 선언 순서대로 정렬된 채 유지되고, service가 방향별로 처리할 때 쓰기 편하다
        Map<ZoneDirection, Long> result = new EnumMap<>(ZoneDirection.class);
        body.forEach((key, neighborId) -> {
            // 키마다 방향 이름인지 확인한다. 값이 null이면 그 방향을 비우라는 뜻이라 그대로 담는다
            try {
                result.put(ZoneDirection.valueOf(key), neighborId);
            } catch (IllegalArgumentException e) {
                // ZoneDirection.valueOf가 던지는 IllegalArgumentException은 서버 오류(500)가 되므로, 사용자 입력 오류(400 INVALID_INPUT)로
                // 바꿔 알린다
                throw new InvalidZoneNeighborException("방향은 N, E, S, W만 쓸 수 있습니다: " + key);
            }
        });
        return result;
    }
}
