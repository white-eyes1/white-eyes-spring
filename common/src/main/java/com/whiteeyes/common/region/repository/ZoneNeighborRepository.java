package com.whiteeyes.common.region.repository;

import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.entity.ZoneNeighborId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneNeighborRepository extends JpaRepository<ZoneNeighbor, ZoneNeighborId> {

    /** 주어진 구역들의 방향별 이웃(FNC-A-016, FNC-C-016). */
    List<ZoneNeighbor> findByZoneIdIn(Collection<Long> zoneIds);
}
