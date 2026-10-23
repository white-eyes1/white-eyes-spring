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

    /** 시·도 목록처럼 한 단위의 지역을 코드 순으로(FNC-C-008). */
    List<Region> findByLevelOrderByCode(RegionLevel level);

    /** 코드가 주어진 앞자리로 시작하는 한 단위의 지역들. 지역 아래의 읍·면·동 찾기에 쓴다(FNC-C-009, decisions/0014). */
    List<Region> findByLevelAndCodeStartingWith(RegionLevel level, String codePrefix);

    /** 바로 아래 지역을 코드 순으로(FNC-C-008). */
    List<Region> findByParent_IdOrderByCode(Long parentId);

    /** 상위 지역(parent)까지 한 번에 불러온다. 지역 경로(시·군·구 · 읍·면·동) 표시에 쓴다(FNC-A-022). */
    @Query("select r from Region r left join fetch r.parent where r.id in :ids")
    List<Region> findAllWithParentByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 이름 또는 상위 지역 이름에 검색어가 들어 있는 한 단위의 지역 번호. 읍·면·동에 쓰면 "동 이름이나 소속 구 이름이 맞는 동"이다
     * (이벤트 목록 검색, FNC-A-036).
     */
    @Query("""
            select r.id from Region r
            left join r.parent p
            where r.level = :level
              and (locate(lower(:keyword), lower(r.name)) > 0 or locate(lower(:keyword), lower(p.name)) > 0)
            """)
    List<Long> findIdsOfLevelByNameOrParentNameContaining(
            @Param("keyword") String keyword, @Param("level") RegionLevel level);

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
