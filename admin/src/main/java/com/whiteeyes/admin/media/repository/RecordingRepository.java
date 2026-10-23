package com.whiteeyes.admin.media.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.admin.media.entity.Recording;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

    /** 그 캠의 종료 시각이 빈 녹화 중 가장 나중에 시작한 것(FNC-A-021 처리 순서 4). */
    Optional<Recording> findFirstByCameraIdAndEndedAtIsNullOrderByStartedAtDesc(Long cameraId);

    /**
     * 그 캠의 진행 중(ended_at 없음)인 녹화 중 closedAt보다 먼저 시작한 것을 closedAt으로 닫는다(FNC-A-026).
     *
     * @return 닫은 녹화 수
     */
    // 늦게 도착한 등록이 더 나중에 시작한 녹화를 과거 시각으로 닫지 않도록 started_at < closedAt만 닫는다
    @Modifying(clearAutomatically = true)
    @Query("update Recording r set r.endedAt = :closedAt"
            + " where r.cameraId = :cameraId and r.endedAt is null and r.startedAt < :closedAt")
    int closeOpenRecordingsStartedBefore(
            @Param("cameraId") Long cameraId, @Param("closedAt") LocalDateTime closedAt);

    /**
     * 주어진 캠들의 녹화 중 at 시각을 포함하는 것(started_at ≤ at, ended_at이 없거나 at보다 뒤)을 시작이 늦은 순으로(FNC-A-043).
     * 첫 줄만 쓰면 started_at이 가장 늦은 녹화다.
     */
    @Query("select r from Recording r where r.cameraId in :cameraIds and r.startedAt <= :at"
            + " and (r.endedAt is null or r.endedAt > :at) order by r.startedAt desc, r.id desc")
    List<Recording> findContaining(
            @Param("cameraIds") Collection<Long> cameraIds, @Param("at") LocalDateTime at, Limit limit);

    /**
     * started_at이 cutoff보다 앞선 녹화 기록을 최대 limit건 지운다(FNC-A-032). 파일은 지우지 않는다.
     *
     * @return 지운 건수
     */
    // 건수 제한이 있는 삭제는 JPQL로 쓸 수 없어 네이티브로 쓴다(MySQL·H2 모두 DELETE ... LIMIT 지원)
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM recording WHERE started_at < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteStartedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 주어진 캠들의 녹화 중 [from, to) 범위와 겹치는 것(started_at &lt; to, ended_at이 없거나 from보다 뒤)을 시작이 빠른 순으로
     * (FNC-A-043 구간 조회).
     */
    @Query("select r from Recording r where r.cameraId in :cameraIds and r.startedAt < :to"
            + " and (r.endedAt is null or r.endedAt > :from) order by r.startedAt asc, r.id asc")
    List<Recording> findOverlapping(
            @Param("cameraIds") Collection<Long> cameraIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 주어진 캠들의 녹화 중 since 이후에 시작한 것 가운데 가장 늦게 시작한 것(FNC-A-043 latest). */
    Optional<Recording> findFirstByCameraIdInAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
            Collection<Long> cameraIds, LocalDateTime since);
}
