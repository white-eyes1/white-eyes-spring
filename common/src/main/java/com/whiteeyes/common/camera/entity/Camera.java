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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

// JPA 엔티티. camera 테이블 한 행(분석 서버가 영상을 분석하는 캠 한 대)에 대응한다. 구역(zone)당 삭제되지 않은 캠은 1대다
@Entity
@Table(name = "camera")
@Getter
// JPA 전용 기본 생성자는 숨기고, 만들 때는 아래 공개 생성자를 쓰게 한다
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Camera {

    // 새 캠의 진행률(준비 진행도) 초기값
    private static final int INITIAL_PROGRESS = 0;

    // 기본키. DB 자동 증가 값
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // zone 도메인의 엔티티를 직접 들지 않고 번호만 둔다(package-structure.md)
    // 캠이 놓인 구역 번호
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    // 캠 이름
    @Column(nullable = false, length = 50)
    private String name;

    // 입력 방식(LIVE 실시간 분석, DUMMY 캐시 재생). 이름 문자열로 저장한다
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private CameraSourceType sourceType;

    // 영상 주소. 입력 방식에 따라 형식 규칙이 다르며 검사는 admin 쪽 서비스가 한다
    @Column(name = "source_uri", nullable = false, length = 255)
    private String sourceUri;

    // "가동해야 한다"는 관리자의 의도. 실제 상태(status)와 따로 둔다: 분석 서버가 재시작하면 이 값이 true인 캠을 다시 켠다
    @Column(name = "run_requested", nullable = false)
    private boolean runRequested;

    // 분석 서버가 보고한 실제 상태. 의도(runRequested)와 다를 수 있다
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CameraStatus status;

    // 진행률은 DB에서 TINYINT라 하이버네이트가 같은 타입으로 매핑하도록 지정한다
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "progress", nullable = false)
    private int progress;

    // 분석 서버가 보고한 오류 내용. 오류가 아니면 null
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    // 처음 가동에 성공한 시각. "한 번이라도 가동한 적 있는가"로 삭제 방식(실제 삭제/삭제 표시)을 가른다
    @Column(name = "first_started_at")
    private LocalDateTime firstStartedAt;

    // 삭제 표시 시각. 값이 있으면 삭제된 캠이고 일반 조회에서는 제외한다(soft delete)
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
    // 새 캠의 초기 상태를 한 곳에서 정한다
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
        // 최초 한 번만 기록해야 하므로 이미 값이 있으면 건드리지 않는다
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
        // 분석 서버가 진행률을 보내지 않은 보고(예: 오류 보고)에서는 마지막 진행률을 지우지 않는다
        if (progress != null) {
            this.progress = progress;
        }
        this.errorMessage = errorMessage;
    }
}
