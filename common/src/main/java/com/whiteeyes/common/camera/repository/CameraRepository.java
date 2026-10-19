package com.whiteeyes.common.camera.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.camera.entity.Camera;

// TODO(#11): 캠 등록(FNC-A-018)에서 정식으로 만든다. FNC-A-025 구현을 위해 먼저 둔 임시 코드
public interface CameraRepository extends JpaRepository<Camera, Long> {

    Optional<Camera> findByIdAndDeletedAtIsNull(Long id);
}
