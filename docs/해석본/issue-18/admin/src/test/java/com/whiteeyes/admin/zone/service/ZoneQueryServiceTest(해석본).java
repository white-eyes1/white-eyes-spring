/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/zone/service/ZoneQueryServiceTest.java
 * 관련 이슈: #18(FNC-A-028). 임시 코드(TODO(#10))의 테스트
 *
 * [무엇] 구역 면적 조회 서비스의 단위 테스트. 리포지토리는 Mockito 가짜로 바꿔 DB 없이 확인한다.
 * [확인하는 것] 있는 구역은 zoneId → 면적으로 돌려주고, 없는 구역(9)은 결과에서 빠지는지.
 *   '빠진다'는 동작에 MinuteStatService가 기대고 있다(map.get()이 null이면 그 구역 저장을 건너뜀).
 */
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

@ExtendWith(MockitoExtension.class)
class ZoneQueryServiceTest {

    @Mock
    private ZoneRepository zoneRepository;

    // [해석] @InjectMocks: 위의 가짜 리포지토리를 생성자에 넣어 진짜 ZoneQueryService를 만든다.
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
    // [해석] 임시 Zone에는 공개 생성자도 setter도 없다(엔티티 규칙). 그래서 테스트에서만 리플렉션으로 빈 객체를 만들고
    //        필드를 채운다. #16·#17 테스트의 Camera 만들기와 같은 방식이고, #10이 정식 생성 메서드를 만들면 바꾼다.
    private Zone zone(long id, String areaM2) {
        Zone zone = BeanUtils.instantiateClass(Zone.class);
        ReflectionTestUtils.setField(zone, "id", id);
        ReflectionTestUtils.setField(zone, "areaM2", new BigDecimal(areaM2));
        return zone;
    }
}
