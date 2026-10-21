package com.whiteeyes.common.camera.repository;

import com.whiteeyes.common.camera.entity.Camera;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    Optional<Camera> findByIdAndDeletedAtIsNull(Long id);

    /** 그 구역에 삭제되지 않은 캠이 있는지 본다(구역당 1대, FNC-A-018 처리 순서 2). */
    boolean existsByZoneIdAndDeletedAtIsNull(Long zoneId);

    /** 삭제되지 않은 모든 캠을 번호 순으로(FNC-A-022 캠 목록). */
    List<Camera> findByDeletedAtIsNullOrderByIdAsc();

    /** 주어진 구역들의 삭제되지 않은 캠을 번호 순으로(FNC-A-022 지역 필터). */
    List<Camera> findByZoneIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> zoneIds);

    /** 주어진 구역 중 삭제되지 않은 캠이 있는 구역 번호(FNC-C-008 hasCamera). */
    @Query("select distinct c.zoneId from Camera c where c.zoneId in :zoneIds and c.deletedAt is null")
    List<Long> findZoneIdsWithActiveCamera(@Param("zoneIds") Collection<Long> zoneIds);
}
