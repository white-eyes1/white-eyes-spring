package com.whiteeyes.common.region.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.region.entity.Zone;

// DeletedAtIsNull이 붙은 메서드는 삭제 표시가 없는 구역만 본다
public interface ZoneRepository extends JpaRepository<Zone, Long> {

    /** 같은 동의 삭제되지 않은 구역 중 이름이 같은 것이 있는지 본다(FNC-A-014 처리 순서 2). */
    boolean existsByDongIdAndNameAndDeletedAtIsNull(Long dongId, String name);

    /** 같은 동의 삭제되지 않은 다른 구역 중 이름이 같은 것이 있는지 본다(FNC-A-015 처리 순서 3). */
    boolean existsByDongIdAndNameAndDeletedAtIsNullAndIdNot(Long dongId, String name, Long id);

    /** 구역이 있고 삭제되지 않았는지 본다(캠 등록 FNC-A-018 처리 순서 1). */
    boolean existsByIdAndDeletedAtIsNull(Long id);

    /** 삭제되지 않은 모든 구역을 번호 순으로(FNC-C-008). */
    List<Zone> findByDeletedAtIsNullOrderById();

    /** 한 동의 삭제되지 않은 구역을 번호 순으로(FNC-C-008). */
    List<Zone> findByDongIdAndDeletedAtIsNullOrderById(Long dongId);

    /** 주어진 읍·면·동에 속한 삭제되지 않은 구역(FNC-C-009). */
    List<Zone> findByDongIdInAndDeletedAtIsNull(Collection<Long> dongIds);

    /** 주어진 읍·면·동에 속한 삭제되지 않은 구역 번호(캠 목록 지역 필터, FNC-A-022). */
    // 번호만 필요한 호출자를 위해 엔티티 전체가 아니라 id만 가져온다
    @Query("select z.id from Zone z where z.dongId in :dongIds and z.deletedAt is null")
    List<Long> findActiveIdsByDongIdIn(@Param("dongIds") Collection<Long> dongIds);

    /** 이름에 검색어가 들어 있는 구역 번호. 삭제된 구역도 포함한다(이벤트 목록 검색, FNC-A-036). */
    // 대소문자를 무시한 부분 일치. deletedAt 조건이 없어 삭제된 구역도 나온다
    @Query("select z.id from Zone z where locate(lower(:keyword), lower(z.name)) > 0")
    List<Long> findIdsByNameContaining(@Param("keyword") String keyword);

    /** 삭제된 구역도 포함한다. 오늘 기록처럼 지난 값을 셀 때 쓴다(FNC-C-016). */
    List<Zone> findByDongIdIn(Collection<Long> dongIds);
}
