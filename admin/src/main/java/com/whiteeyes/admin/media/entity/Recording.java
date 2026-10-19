package com.whiteeyes.admin.media.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 관리자 앱만 쓰는 테이블이라 admin 모듈에 둔다(package-structure.md 4장)
@Entity
@Table(name = "recording")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recording {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 도메인(camera)의 엔티티를 직접 참조하지 않고 id 값으로 둔다(package-structure.md 3-2)
    @Column(name = "camera_id", nullable = false)
    private Long cameraId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "video_path", nullable = false, length = 255)
    private String videoPath;

    @Column(name = "box_path", nullable = false, length = 255)
    private String boxPath;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Recording(
            Long cameraId, LocalDateTime startedAt, LocalDateTime endedAt, String videoPath, String boxPath) {
        this.cameraId = cameraId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.videoPath = videoPath;
        this.boxPath = boxPath;
    }

    /**
     * 분석 서버가 보낸 녹화를 등록한다(FNC-A-026). 더미 캠은 endedAt 없이(진행 중) 등록한다.
     */
    public static Recording register(
            Long cameraId, LocalDateTime startedAt, LocalDateTime endedAt, String videoPath, String boxPath) {
        return new Recording(cameraId, startedAt, endedAt, videoPath, boxPath);
    }
}
