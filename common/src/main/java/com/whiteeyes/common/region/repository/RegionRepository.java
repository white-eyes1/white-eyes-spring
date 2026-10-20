package com.whiteeyes.common.region.repository;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByCodeIn(Collection<String> codes);

    List<Region> findByLevel(RegionLevel level);

    /** 상위 지역(parent)까지 한 번에 불러온다. 지역 경로(시·군·구 · 읍·면·동) 표시에 쓴다(FNC-A-022). */
    @Query("select r from Region r left join fetch r.parent where r.id in :ids")
    List<Region> findAllWithParentByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 지역(시·도, 시·군·구, 읍·면·동 중 아무거나) 아래에 있는 읍·면·동 번호를 구한다. 읍·면·동 자신을 주면 자기 자신이다
     * (FNC-A-022 regionId 필터).
     */
    @Query("""
            select r.id from Region r
            left join r.parent p
            left join p.parent g
            where r.level = :level and (r.id = :regionId or p.id = :regionId or g.id = :regionId)
            """)
    List<Long> findIdsOfLevelUnder(@Param("regionId") Long regionId, @Param("level") RegionLevel level);
}
