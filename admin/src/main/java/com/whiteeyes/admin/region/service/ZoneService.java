package com.whiteeyes.admin.region.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;

/** 다른 도메인(캠)이 구역을 확인·조회할 때 부르는 구역 도메인의 공개 service. */
@Service
@RequiredArgsConstructor
public class ZoneService {

    // 다른 도메인이 구역 저장소를 직접 열지 않고 이 service를 거치게 한다
    private final ZoneRepository zoneRepository;

    /** 구역이 있고 삭제되지 않았는지 확인한다. 아니면 404 ZONE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public void requireActiveZone(Long zoneId) {
        // 행 전체를 읽지 않고 존재 여부만 확인한다
        if (!zoneRepository.existsByIdAndDeletedAtIsNull(zoneId)) {
            throw new ZoneNotFoundException(zoneId);
        }
    }

    /**
     * 구역의 현재 면적(㎡)을 zoneId → 면적으로 돌려준다(FNC-A-028 밀집도 계산). 없는 구역은 빠진다.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> findAreasByZoneIds(Set<Long> zoneIds) {
        // 구역 번호 → 면적. 번호가 목록에 없으면 결과에서 빠진다
        return zoneRepository.findAllById(zoneIds).stream().collect(Collectors.toMap(Zone::getId, Zone::getAreaM2));
    }

    /** 구역 번호 → 구역 요약. 쿼리 한 번으로 모아서 가져온다(캠 목록 FNC-A-022). 없는 번호는 빠진다. */
    @Transactional(readOnly = true)
    public Map<Long, ZoneSummary> findSummaries(Collection<Long> zoneIds) {
        // 번호들을 한 번에 조회해 구역 → 요약 맵으로 만든다(삭제된 구역도 deleted=true로 포함된다)
        return zoneRepository.findAllById(zoneIds).stream()
                .map(ZoneSummary::from)
                .collect(Collectors.toMap(ZoneSummary::id, Function.identity()));
    }

    /** 주어진 읍·면·동들에 속한 구역 번호. 삭제된 구역도 포함한다(이벤트 목록 지역 필터·검색, FNC-A-036). */
    @Transactional(readOnly = true)
    public List<Long> findAllZoneIdsInDongs(Collection<Long> dongIds) {
        // 삭제 여부를 거르지 않는다. 지난 이벤트는 삭제된 구역 것도 목록에 남아 있어야 하기 때문이다
        return zoneRepository.findByDongIdIn(dongIds).stream().map(Zone::getId).toList();
    }

    /** 이름에 검색어가 들어 있는 구역 번호. 삭제된 구역도 포함한다(이벤트 목록 검색, FNC-A-036). */
    @Transactional(readOnly = true)
    public List<Long> findZoneIdsByNameContaining(String keyword) {
        // 삭제된 구역도 이름으로 찾을 수 있게 필터 없이 조회한다
        return zoneRepository.findIdsByNameContaining(keyword);
    }

    /** 삭제되지 않은 모든 구역의 요약을 번호 순으로(시연용 통계 생성, FNC-A-051). */
    @Transactional(readOnly = true)
    public List<ZoneSummary> findActiveSummaries() {
        // 삭제 표시가 없는 구역만, 번호 순으로
        return zoneRepository.findByDeletedAtIsNullOrderById().stream()
                .map(ZoneSummary::from)
                .toList();
    }

    /** 주어진 읍·면·동들에 속한 삭제되지 않은 구역 번호(캠 목록 지역 필터, FNC-A-022). */
    @Transactional(readOnly = true)
    public List<Long> findActiveZoneIdsInDongs(Collection<Long> dongIds) {
        // findAllZoneIdsInDongs와 달리 삭제된 구역은 제외한다(캠 목록은 현재 구역 기준)
        return zoneRepository.findActiveIdsByDongIdIn(dongIds);
    }
}
