package com.whiteeyes.common.camera.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TODO(#11): 캠 등록(FNC-A-018)에서 tables.md 'camera'의 전체 칼럼으로 정식으로 만든다.
// 지금은 FNC-A-025에 필요한 칼럼만 둔 임시 코드
@Entity
@Table(name = "camera")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Camera {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_requested", nullable = false)
    private boolean runRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CameraStatus status;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
