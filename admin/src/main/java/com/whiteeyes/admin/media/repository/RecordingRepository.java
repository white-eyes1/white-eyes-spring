package com.whiteeyes.admin.media.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
