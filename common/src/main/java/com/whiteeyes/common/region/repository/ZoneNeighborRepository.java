package com.whiteeyes.common.region.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.entity.ZoneNeighborId;

// 기본키 타입이 복합 키(ZoneNeighborId)다
public interface ZoneNeighborRepository extends JpaRepository<ZoneNeighbor, ZoneNeighborId> {

    /** 주어진 구역들의 방향별 이웃(FNC-A-016, FNC-C-016). */
    List<ZoneNeighbor> findByZoneIdIn(Collection<Long> zoneIds);

    /** 이 구역을 기준으로 가진 행과 이웃으로 가리키는 행을 모두 지운다(구역 삭제 FNC-A-017). @return 지운 행 수 */
    // 대량 삭제 쿼리라 실행 전 flush, 실행 후 영속성 컨텍스트를 비워 지운 행이 메모리에 남아 어긋나지 않게 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    // 구역이 지워지면 이 구역이 기준인 행과 이 구역을 이웃으로 가리키는 행을 모두 지운다
    @Query("delete from ZoneNeighbor n where n.zoneId = :zoneId or n.neighborId = :zoneId")
    int deleteAllInvolving(@Param("zoneId") Long zoneId);
}
