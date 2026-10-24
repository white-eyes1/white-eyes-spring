package com.whiteeyes.common.camera.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "camera")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Camera {

    private static final int INITIAL_PROGRESS = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // zone 도메인의 엔티티를 직접 들지 않고 번호만 둔다(package-structure.md)
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private CameraSourceType sourceType;

    @Column(name = "source_uri", nullable = false, length = 255)
    private String sourceUri;

    @Column(name = "run_requested", nullable = false)
    private boolean runRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CameraStatus status;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "first_started_at")
    private LocalDateTime firstStartedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 아래 두 시각은 DB 기본값(CURRENT_TIMESTAMP, updated_at은 ON UPDATE)이 채운다
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /** 새 캠은 정지 상태(STOPPED), 가동 요청 없음(run_requested=0)으로 만든다(FNC-A-018 처리 순서 4). */
    public Camera(Long zoneId, String name, CameraSourceType sourceType, String sourceUri) {
        this.zoneId = zoneId;
        this.name = name;
        this.sourceType = sourceType;
        this.sourceUri = sourceUri;
        this.runRequested = false;
        this.status = CameraStatus.STOPPED;
        this.progress = INITIAL_PROGRESS;
    }

    /** 정지 상태인 캠의 이름·입력 방식·영상 주소를 바꾼다(FNC-A-019). 정지 확인과 주소 규칙 검사는 호출하는 쪽이 먼저 한다. */
    public void updateInfo(String name, CameraSourceType sourceType, String sourceUri) {
        this.name = name;
        this.sourceType = sourceType;
        this.sourceUri = sourceUri;
    }

    /** 분석 서버가 이 캠을 가동해야 한다고 기록한다. 분석 서버가 다시 켜지면 이 값을 보고 자동 가동한다(FNC-A-020 처리 순서 2). */
    public void requestRun() {
        this.runRequested = true;
    }

    /** 분석 서버가 이 캠을 가동하지 않아도 된다고 기록한다. 분석 서버가 다시 켜져도 가동하지 않는다(FNC-A-021 처리 순서 1). */
    public void cancelRunRequest() {
        this.runRequested = false;
    }

    /** 처음 가동에 성공한 시각을 기록한다. 이미 있으면 바꾸지 않는다(FNC-A-020 처리 순서 4). */
    public void markFirstStartedIfAbsent(LocalDateTime now) {
        if (this.firstStartedAt == null) {
            this.firstStartedAt = now;
        }
    }

    /** 삭제 표시만 한다. 가동 이력이 있는 캠은 지난 녹화·통계가 이 캠을 가리키므로 행을 남긴다(FNC-A-023 처리 순서 2). */
    public void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
    }

    /**
     * 분석 서버가 보고한 상태로 바꾼다(FNC-A-025). progress가 null이면 기존 진행률을 유지한다.
     */
    public void updateStatus(CameraStatus status, Integer progress, String errorMessage) {
        this.status = status;
        if (progress != null) {
            this.progress = progress;
        }
        this.errorMessage = errorMessage;
    }
}
