package com.whiteeyes.common.region.repository;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByCodeIn(Collection<String> codes);

    List<Region> findByLevel(RegionLevel level);

    List<Region> findByParent_IdOrderByCode(Long parentId);

    /** 코드 앞자리가 상위 코드라서(decisions/0014) 지역 아래의 읍·면·동을 한 번에 찾을 수 있다. */
    List<Region> findByLevelAndCodeStartingWith(RegionLevel level, String codePrefix);
}
