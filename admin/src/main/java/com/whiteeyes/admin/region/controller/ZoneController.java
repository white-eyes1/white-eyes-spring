package com.whiteeyes.admin.region.controller;

import com.whiteeyes.admin.region.dto.ZoneCreateRequest;
import com.whiteeyes.admin.region.dto.ZoneCreateResponse;
import com.whiteeyes.admin.region.dto.ZoneNeighborRef;
import com.whiteeyes.admin.region.dto.ZoneUpdateRequest;
import com.whiteeyes.admin.region.dto.ZoneUpdateResponse;
import com.whiteeyes.admin.region.exception.InvalidZoneNeighborException;
import com.whiteeyes.admin.region.service.ZoneNeighborService;
import com.whiteeyes.admin.region.service.ZoneRegisterService;
import com.whiteeyes.admin.region.service.ZoneUpdateService;
import com.whiteeyes.common.region.entity.ZoneDirection;
import jakarta.validation.Valid;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manage/zones")
@RequiredArgsConstructor
public class ZoneController {

    private final ZoneRegisterService zoneRegisterService;
    private final ZoneUpdateService zoneUpdateService;
    private final ZoneNeighborService zoneNeighborService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneCreateResponse register(@Valid @RequestBody ZoneCreateRequest request) {
        return zoneRegisterService.register(request.name(), request.boundary());
    }

    /** FNC-A-015 구역 수정: 보낸 필드(이름·다각형)만 고친다. 다각형은 같은 동 안에서만 바꿀 수 있다. */
    @PatchMapping("/{id}")
    public ZoneUpdateResponse update(@PathVariable("id") Long id, @Valid @RequestBody ZoneUpdateRequest request) {
        return zoneUpdateService.update(id, request.name(), request.boundary());
    }

    /** FNC-A-016 구역 이웃 조회: 4방향 이웃(없는 방향은 null). */
    @GetMapping("/{id}/neighbors")
    public Map<String, ZoneNeighborRef> findNeighbors(@PathVariable("id") Long id) {
        return zoneNeighborService.find(id);
    }

    /** FNC-A-016 구역 이웃 저장: 4방향 이웃을 저장하고 반대쪽 구역의 반대 방향도 맞춘다. */
    @PutMapping("/{id}/neighbors")
    public Map<String, ZoneNeighborRef> saveNeighbors(
            @PathVariable("id") Long id, @RequestBody Map<String, Long> body) {
        return zoneNeighborService.save(id, toDirections(body));
    }

    // 키는 N·E·S·W만 받는다(그 밖의 키는 400). 빠진 방향은 이웃 없음으로 본다
    private Map<ZoneDirection, Long> toDirections(Map<String, Long> body) {
        Map<ZoneDirection, Long> result = new EnumMap<>(ZoneDirection.class);
        body.forEach((key, neighborId) -> {
            try {
                result.put(ZoneDirection.valueOf(key), neighborId);
            } catch (IllegalArgumentException e) {
                throw new InvalidZoneNeighborException("방향은 N, E, S, W만 쓸 수 있습니다: " + key);
            }
        });
        return result;
    }
}
