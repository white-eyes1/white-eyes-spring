package com.whiteeyes.common.camera.repository;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    Optional<Camera> findByIdAndDeletedAtIsNull(Long id);

    /** 그 구역에 삭제되지 않은 캠이 있는지 본다(구역당 1대, FNC-A-018 처리 순서 2). */
    boolean existsByZoneIdAndDeletedAtIsNull(Long zoneId);

    /** 삭제되지 않은 모든 캠을 번호 순으로(FNC-A-022 캠 목록). */
    List<Camera> findByDeletedAtIsNullOrderByIdAsc();

    /** 가동 요청이 있고 삭제되지 않은 캠을 번호 순으로(FNC-A-027 가동 대상 캠 목록). 상태는 보지 않아 오류 캠도 포함한다. */
    List<Camera> findByRunRequestedTrueAndDeletedAtIsNullOrderByIdAsc();

    /** 주어진 구역들의 삭제되지 않은 캠을 번호 순으로(FNC-A-022 지역 필터). */
    List<Camera> findByZoneIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> zoneIds);

    /** 주어진 구역 중 삭제되지 않은 캠이 있는 구역 번호(FNC-C-008 hasCamera). */
    @Query("select distinct c.zoneId from Camera c where c.zoneId in :zoneIds and c.deletedAt is null")
    List<Long> findZoneIdsWithActiveCamera(@Param("zoneIds") Collection<Long> zoneIds);

    /** 그 구역에 있었던 모든 캠 번호. 삭제된 캠도 포함한다(지난 녹화를 찾는 다시보기 FNC-A-043). */
    @Query("select c.id from Camera c where c.zoneId = :zoneId")
    List<Long> findIdsByZoneId(@Param("zoneId") Long zoneId);

    /** 그 구역의 삭제되지 않은 캠 중 주어진 상태인 것이 있는지 본다(구역 삭제 FNC-A-017 처리 순서 1). */
    boolean existsByZoneIdAndDeletedAtIsNullAndStatusIn(Long zoneId, Collection<CameraStatus> statuses);

    /** 그 구역에 처음 가동한 적이 있는 캠이 있는지 본다. 삭제된 캠도 포함한다(구역 삭제 FNC-A-017 처리 순서 2). */
    boolean existsByZoneIdAndFirstStartedAtIsNotNull(Long zoneId);

    /** 그 구역의 캠을 모두 실제로 지운다. 삭제된 캠도 포함한다(구역 삭제, 이력 없음). @return 지운 캠 수 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Camera c where c.zoneId = :zoneId")
    int deleteAllByZoneId(@Param("zoneId") Long zoneId);

    /** 그 구역의 삭제되지 않은 캠에 삭제 시각을 넣는다(구역 삭제, 이력 있음). 이미 삭제된 캠의 시각은 그대로 둔다. @return 바꾼 캠 수 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Camera c set c.deletedAt = :now where c.zoneId = :zoneId and c.deletedAt is null")
    int softDeleteAllByZoneId(@Param("zoneId") Long zoneId, @Param("now") LocalDateTime now);

    /** 구역들의 삭제되지 않은 캠 수(현재 현황 요약 FNC-C-016). */
    long countByZoneIdInAndDeletedAtIsNull(Collection<Long> zoneIds);

    long countByZoneIdInAndDeletedAtIsNullAndStatus(Collection<Long> zoneIds, CameraStatus status);
}
