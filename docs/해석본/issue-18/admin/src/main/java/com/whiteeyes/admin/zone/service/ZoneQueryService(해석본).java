/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/zone/service/ZoneQueryService.java
 * 관련 이슈: #18(FNC-A-028). 임시 코드, 정식 코드는 #10(FNC-A-014 구역 등록)의 ZoneService
 *
 * [무엇] stat 도메인(1분 통계 저장)에 구역 면적을 내주는 서비스.
 * [왜 따로 두나] package-structure.md 규칙: "다른 도메인이 필요하면 그 도메인의 service만 부른다.
 *   다른 도메인의 repository·엔티티를 직접 쓰지 않는다." stat이 ZoneRepository를 바로 쓰면 이 규칙을 어긴다.
 *   #16의 CameraQueryService(stat에 cameraId → zoneId를 내줌)와 같은 방식이다.
 * [염두] 한 분이 끝나면 여러 구역이 한꺼번에 마감된다. 구역마다 따로 조회하면 구역 수만큼 쿼리가 나가므로,
 *   구역 id 묶음을 받아 쿼리 한 번(findAllById)으로 조회한다.
 */
package com.whiteeyes.admin.zone.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.zone.entity.Zone;
import com.whiteeyes.common.zone.repository.ZoneRepository;

// TODO(#10): 구역 등록(FNC-A-014)에서 ZoneService가 생기면 그쪽으로 옮긴다
@Service
public class ZoneQueryService {

    private final ZoneRepository zoneRepository;

    // [해석] 생성자 주입: 필드에 @Autowired를 붙이지 않고 생성자로 받는다(code-conventions). 테스트에서 가짜(mock)를 넣기 쉽다.
    public ZoneQueryService(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /**
     * 구역의 현재 면적(㎡)을 zoneId → 면적으로 돌려준다(FNC-A-028 밀집도 계산). 없는 구역은 빠진다.
     */
    // [해석] readOnly = true: 읽기만 하는 트랜잭션이라고 알려서 JPA가 변경 감지를 하지 않게 한다(조금 더 가볍다).
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> findAreasByZoneIds(Set<Long> zoneIds) {
        // [해석] 없는 구역은 결과에 아예 안 들어간다. 그래서 호출한 쪽(MinuteStatService)은 map.get()이 null이면
        //        '면적 없음'으로 보고 그 구역 저장을 건너뛴다. 예외를 던지지 않는 이유: 한 구역 문제로 다른 구역까지
        //        저장이 막히면 안 되기 때문.
        return zoneRepository.findAllById(zoneIds).stream()
                .collect(Collectors.toMap(Zone::getId, Zone::getAreaM2));
    }
}
