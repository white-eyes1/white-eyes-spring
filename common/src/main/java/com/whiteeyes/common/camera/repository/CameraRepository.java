package com.whiteeyes.common.camera.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;

// 메서드 이름 규칙(findBy..., existsBy...)으로 쿼리가 만들어진다. DeletedAtIsNull은 삭제 표시가 없는 캠만 본다는 뜻이다
public interface CameraRepository extends JpaRepository<Camera, Long> {

    /** 번호로 삭제되지 않은 캠 한 건을 찾는다. */
    Optional<Camera> findByIdAndDeletedAtIsNull(Long id);

    /** 행을 잠그고 읽는다(FNC-A-023 캠 삭제). 같은 시각에 가동·정지가 상태를 바꾸는 경합을 막는다. */
    // SELECT ... FOR UPDATE로 행을 잠가 다른 트랜잭션의 상태 변경을 기다리게 한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Camera> findWithLockByIdAndDeletedAtIsNull(Long id);

    /** 그 구역에 삭제되지 않은 캠이 있는지 본다(구역당 1대, FNC-A-018 처리 순서 2). */
    boolean existsByZoneIdAndDeletedAtIsNull(Long zoneId);

    /** 삭제되지 않은 모든 캠을 번호 순으로(FNC-A-022 캠 목록). */
    List<Camera> findByDeletedAtIsNullOrderByIdAsc();

    /** 가동 요청이 있고 삭제되지 않은 캠을 번호 순으로(FNC-A-027 가동 대상 캠 목록). 상태는 보지 않아 오류 캠도 포함한다. */
    List<Camera> findByRunRequestedTrueAndDeletedAtIsNullOrderByIdAsc();

    /** 주어진 구역들의 삭제되지 않은 캠을 번호 순으로(FNC-A-022 지역 필터). */
    List<Camera> findByZoneIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> zoneIds);

    /** 주어진 구역 중 삭제되지 않은 캠이 있는 구역 번호(FNC-C-008 hasCamera). */
    // 구역별로 캠이 여러 번 나오지 않도록 distinct. 구역 번호만 가져와 가볍게 한다
    @Query("select distinct c.zoneId from Camera c where c.zoneId in :zoneIds and c.deletedAt is null")
    List<Long> findZoneIdsWithActiveCamera(@Param("zoneIds") Collection<Long> zoneIds);

    /** 그 구역에 있었던 모든 캠 번호. 삭제된 캠도 포함한다(지난 녹화를 찾는 다시보기 FNC-A-043). */
    // deletedAt 조건이 없어 삭제된 캠 번호도 나온다. 지난 녹화는 삭제된 캠을 가리키기 때문이다
    @Query("select c.id from Camera c where c.zoneId = :zoneId")
    List<Long> findIdsByZoneId(@Param("zoneId") Long zoneId);

    /** 이름에 검색어가 들어 있는 캠의 구역 번호. 삭제된 캠도 포함한다(이벤트 목록 검색, FNC-A-036). */
    // 대소문자를 무시한 부분 일치 검색. LIKE 대신 locate를 써 검색어의 %·_ 같은 문자를 와일드카드로 해석하지 않게 한다
    @Query("select distinct c.zoneId from Camera c where locate(lower(:keyword), lower(c.name)) > 0")
    List<Long> findZoneIdsByNameContaining(@Param("keyword") String keyword);

    /** 그 구역의 삭제되지 않은 캠 중 주어진 상태인 것이 있는지 본다(구역 삭제 FNC-A-017 처리 순서 1). */
    boolean existsByZoneIdAndDeletedAtIsNullAndStatusIn(Long zoneId, Collection<CameraStatus> statuses);

    /** 그 구역의 삭제되지 않은 캠 중 주어진 상태이거나 가동 요청(run_requested=1) 상태인 것이 있는지 본다(구역 삭제 FNC-A-017 처리 순서 1). */
    // 상태가 지정 목록에 있거나 가동 요청이 켜진 캠이 있는지. count > 0로 존재 여부만 받는다. 가동 요청이 있으면 곧 켜질 캠이라 구역을 지우면 안 된다
    @Query(
            """
            select count(c) > 0 from Camera c
            where c.zoneId = :zoneId and c.deletedAt is null and (c.status in :statuses or c.runRequested = true)
            """)
    boolean existsActiveOrRunRequested(
            @Param("zoneId") Long zoneId, @Param("statuses") Collection<CameraStatus> statuses);

    /** 처음 가동한 적이 있는 모든 캠. 삭제된 캠도 포함한다(시연용 통계를 가동 시작 전날까지만 만드는 기준, FNC-A-051). */
    List<Camera> findByFirstStartedAtIsNotNull();

    /** 그 구역에 처음 가동한 적이 있는 캠이 있는지 본다. 삭제된 캠도 포함한다(구역 삭제 FNC-A-017 처리 순서 2). */
    boolean existsByZoneIdAndFirstStartedAtIsNotNull(Long zoneId);

    /** 그 구역의 캠을 모두 실제로 지운다. 삭제된 캠도 포함한다(구역 삭제, 이력 없음). @return 지운 캠 수 */
    // 대량 삭제 쿼리. 실행 전에 미반영 변경을 DB로 내보내고(flush), 실행 후 영속성 컨텍스트를 비워(clear) 지워진 엔티티가 메모리에 남아 어긋나지 않게 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Camera c where c.zoneId = :zoneId")
    int deleteAllByZoneId(@Param("zoneId") Long zoneId);

    /** 그 구역의 삭제되지 않은 캠에 삭제 시각을 넣는다(구역 삭제, 이력 있음). 이미 삭제된 캠의 시각은 그대로 둔다. @return 바꾼 캠 수 */
    // 위와 같은 이유. 삭제 시각 일괄 갱신 쿼리라 메모리의 엔티티와 어긋나지 않도록 flush·clear 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Camera c set c.deletedAt = :now where c.zoneId = :zoneId and c.deletedAt is null")
    int softDeleteAllByZoneId(@Param("zoneId") Long zoneId, @Param("now") LocalDateTime now);

    /** 구역들의 삭제되지 않은 캠 수(현재 현황 요약 FNC-C-016). */
    long countByZoneIdInAndDeletedAtIsNull(Collection<Long> zoneIds);

    /** 구역들의 삭제되지 않은 캠 중 주어진 상태인 캠 수. */
    long countByZoneIdInAndDeletedAtIsNullAndStatus(Collection<Long> zoneIds, CameraStatus status);
}
