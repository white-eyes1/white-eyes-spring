package com.whiteeyes.admin.zone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.whiteeyes.common.zone.entity.Zone;
import com.whiteeyes.common.zone.repository.ZoneRepository;

/** 1분 통계 저장(FNC-A-028) */
@ExtendWith(MockitoExtension.class)
class ZoneQueryServiceTest {

    @Mock
    private ZoneRepository zoneRepository;

    @InjectMocks
    private ZoneQueryService zoneQueryService;

    @Test
    @DisplayName("FNC-A-028 구역 면적을 zoneId → 면적으로 돌려주고 없는 구역은 빠짐")
    void returnsAreasOfExistingZones() {
        given(zoneRepository.findAllById(Set.of(3L, 9L))).willReturn(List.of(zone(3L, "150.00")));

        Map<Long, BigDecimal> areaByZone = zoneQueryService.findAreasByZoneIds(Set.of(3L, 9L));

        assertThat(areaByZone).containsExactly(Map.entry(3L, new BigDecimal("150.00")));
    }

    // TODO(#10): Zone에 생성 메서드가 생기면 그것으로 바꾼다
    private Zone zone(long id, String areaM2) {
        Zone zone = BeanUtils.instantiateClass(Zone.class);
        ReflectionTestUtils.setField(zone, "id", id);
        ReflectionTestUtils.setField(zone, "areaM2", new BigDecimal(areaM2));
        return zone;
    }
}
