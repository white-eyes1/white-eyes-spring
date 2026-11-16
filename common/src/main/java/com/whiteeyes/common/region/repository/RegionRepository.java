package com.whiteeyes.common.region.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;

// 메서드 이름 규칙으로 쿼리가 만들어진다. fetch join이 필요한 것은 @Query를 쓴다
public interface RegionRepository extends JpaRepository<Region, Long> {

    /** 코드 목록에 해당하는 지역을 한 번에 찾는다(경계 파일 다시 적재 때 기존 행 확인). */
    List<Region> findByCodeIn(Collection<String> codes);

    /** 한 단위(단계)의 모든 지역. */
    List<Region> findByLevel(RegionLevel level);

    /** 시·도 목록처럼 한 단위의 지역을 코드 순으로(FNC-C-008). */
    List<Region> findByLevelOrderByCode(RegionLevel level);

    /** 코드가 주어진 앞자리로 시작하는 한 단위의 지역들. 지역 아래의 읍·면·동 찾기에 쓴다(FNC-C-009, decisions/0014). */
    List<Region> findByLevelAndCodeStartingWith(RegionLevel level, String codePrefix);

    /** 바로 아래 지역을 코드 순으로(FNC-C-008). */
    List<Region> findByParent_IdOrderByCode(Long parentId);

    /** 상위 지역(parent)까지 한 번에 불러온다. 지역 경로(시·군·구 · 읍·면·동) 표시에 쓴다(FNC-A-022). */
    // fetch join으로 상위 지역을 같이 읽어 지역마다 추가 쿼리가 나가는 N+1 문제를 막는다. left join이라 상위가 없는 지역도 결과에 남는다
    @Query("select r from Region r left join fetch r.parent where r.id in :ids")
    List<Region> findAllWithParentByIdIn(@Param("ids") Collection<Long> ids);

    /** 상위 두 단계(시·군·구, 시·도)까지 한 번에 불러온다. 읍·면·동의 시·도 / 시·군·구 / 읍·면·동 이름을 나눠 쓸 때 쓴다(FNC-A-039). */
    // 두 단계 위(시·도)까지 한 번에 읽는다
    @Query("select r from Region r left join fetch r.parent p left join fetch p.parent where r.id in :ids")
    List<Region> findAllWithAncestorsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 이름 또는 상위 지역 이름에 검색어가 들어 있는 한 단위의 지역 번호. 읍·면·동에 쓰면 "동 이름이나 소속 구 이름이 맞는 동"이다
     * (이벤트 목록 검색, FNC-A-036).
     */
    // 이름 비교는 locate(검색어, 이름) > 0로 부분 일치를 보며 대소문자는 무시한다. 상위(구)가 없는 경우도 있어 left join이다
    @Query(
            """
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
    // 대상 지역이 자기 자신이거나, 부모가 그 지역이거나, 조부모가 그 지역이면 그 아래(또는 자신)의 읍·면·동이다. 조회 단계는 :level로 한정한다
    @Query(
            """
            select r.id from Region r
            left join r.parent p
            left join p.parent g
            where r.level = :level and (r.id = :regionId or p.id = :regionId or g.id = :regionId)
            """)
    List<Long> findIdsOfLevelUnder(@Param("regionId") Long regionId, @Param("level") RegionLevel level);
}
