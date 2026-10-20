package com.whiteeyes.admin.region.service;

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
import org.springframework.test.util.ReflectionTestUtils;

import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.repository.ZoneRepository;

/** 1분 통계 저장(FNC-A-028) */
@ExtendWith(MockitoExtension.class)
class ZoneServiceTest {

    @Mock
    private ZoneRepository zoneRepository;

    @InjectMocks
    private ZoneService zoneService;

    @Test
    @DisplayName("FNC-A-028 구역 면적을 zoneId → 면적으로 돌려주고 없는 구역은 빠짐")
    void returnsAreasOfExistingZones() {
        given(zoneRepository.findAllById(Set.of(3L, 9L))).willReturn(List.of(zone(3L, "150.00")));

        Map<Long, BigDecimal> areaByZone = zoneService.findAreasByZoneIds(Set.of(3L, 9L));

        assertThat(areaByZone).containsExactly(Map.entry(3L, new BigDecimal("150.00")));
    }

    // id는 생성자로 정할 수 없어 직접 넣는다
    private Zone zone(long id, String areaM2) {
        Zone zone = new Zone(1L, "구역" + id, new BigDecimal(areaM2), "{}", new BigDecimal("37.600000"),
                new BigDecimal("127.002000"));
        ReflectionTestUtils.setField(zone, "id", id);
        return zone;
    }
}
