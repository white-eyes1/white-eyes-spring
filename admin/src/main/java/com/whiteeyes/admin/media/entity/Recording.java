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
/** 녹화 한 건(영상 파일 하나와 사람 좌표 파일 하나)의 기록. 파일 자체는 공유 폴더에 있고 DB에는 경로만 저장한다. */
@Entity
@Table(name = "recording")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recording {

    // 기본키: DB의 자동 증가 값을 쓴다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 도메인(camera)의 엔티티를 직접 참조하지 않고 id 값으로 둔다(package-structure.md 3-2)
    @Column(name = "camera_id", nullable = false)
    private Long cameraId;

    // 녹화 시작 시각(KST, 시간대 없이 저장).
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    // 녹화 종료 시각. 진행 중인 녹화(더미 캠)는 비어 있다.
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    // 공유 폴더 기준 영상 파일 상대경로.
    @Column(name = "video_path", nullable = false, length = 255)
    private String videoPath;

    // 공유 폴더 기준 사람 좌표(gzip JSON) 파일 상대경로.
    @Column(name = "box_path", nullable = false, length = 255)
    private String boxPath;

    // 행이 처음 저장될 때 하이버네이트가 자동으로 채우는 생성 시각.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 외부에서 직접 만들지 못하게 막고 register()로만 만들게 한다.
    private Recording(Long cameraId, LocalDateTime startedAt, LocalDateTime endedAt, String videoPath, String boxPath) {
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

    /** 진행 중인 녹화를 닫는다(FNC-A-021 처리 순서 4). 시작 시각보다 앞선 종료 시각은 시작 시각으로 맞춘다. */
    public void close(LocalDateTime closedAt) {
        // 종료 시각이 시작보다 앞서는 값이 들어오면(시계 어긋남 등) 시작 시각으로 맞춰 길이가 음수가 되지 않게 한다.
        this.endedAt = closedAt.isBefore(startedAt) ? startedAt : closedAt;
    }
}
