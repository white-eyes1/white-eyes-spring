package com.whiteeyes.common.history.entity;

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

/** 작업 기록 한 줄. 쌓기만 하고 고치거나 지우지 않는다(FNC-C-026). */
@Entity
@Table(name = "work_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;

    // user_account.id 또는 admin_account.id. 두 테이블 중 하나를 가리키므로 FK를 걸지 않는다
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "actor_login_id", nullable = false, length = 20)
    private String actorLoginId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    private WorkTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_name", length = 100)
    private String targetName;

    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    // DB 기본값(CURRENT_TIMESTAMP)이 채운다
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public WorkHistory(
            ActorType actorType,
            Long actorId,
            String actorLoginId,
            WorkAction action,
            WorkTargetType targetType,
            Long targetId,
            String targetName,
            String detail) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorLoginId = actorLoginId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetName = targetName;
        this.detail = detail;
    }
}
