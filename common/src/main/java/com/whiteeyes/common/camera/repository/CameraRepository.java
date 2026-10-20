package com.whiteeyes.common.camera.repository;

import com.whiteeyes.common.camera.entity.Camera;
import com.whiteeyes.common.camera.entity.CameraStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    Optional<Camera> findByIdAndDeletedAtIsNull(Long id);

    /** 그 구역에 삭제되지 않은 캠이 있는지 본다(구역당 1대, FNC-A-018 처리 순서 2). */
    boolean existsByZoneIdAndDeletedAtIsNull(Long zoneId);

    /** 구역들의 삭제되지 않은 캠 수(현재 현황 요약 FNC-C-016). */
    long countByZoneIdInAndDeletedAtIsNull(Collection<Long> zoneIds);

    long countByZoneIdInAndDeletedAtIsNullAndStatus(Collection<Long> zoneIds, CameraStatus status);
}
