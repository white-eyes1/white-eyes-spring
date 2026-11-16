package com.whiteeyes.common.history.entity;

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

/** 작업 기록 한 줄. 쌓기만 하고 고치거나 지우지 않는다(FNC-C-026). */
// JPA 엔티티. work_history 테이블 한 행 = "누가 무엇을 했다"는 기록 한 줄. 감사(추적) 용도라 한 번 쌓으면 바꾸지 않는다
@Entity
@Table(name = "work_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkHistory {

    // 기본키. DB 자동 증가 값
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 행동한 계정의 종류(사용자/관리자). 두 계정 테이블의 번호가 겹칠 수 있어 종류와 번호를 함께 저장한다
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;

    // user_account.id 또는 admin_account.id. 두 테이블 중 하나를 가리키므로 FK를 걸지 않는다
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    // 기록 당시의 아이디. 계정이 나중에 탈퇴·삭제돼도 누가 했는지 남도록 값을 복사해 둔다
    @Column(name = "actor_login_id", nullable = false, length = 20)
    private String actorLoginId;

    @Enumerated(EnumType.STRING)
    // 행동 종류(WorkAction)
    @Column(nullable = false, length = 30)
    private WorkAction action;

    @Enumerated(EnumType.STRING)
    // 행동의 대상 종류. 대상이 없는 행동이면 null
    @Column(name = "target_type", length = 20)
    private WorkTargetType targetType;

    // 대상 번호. 엑셀 전체 다운로드처럼 특정 대상이 없으면 null
    @Column(name = "target_id")
    private Long targetId;

    // 기록 당시의 대상 이름(구역 이름, 계정 아이디 등). 대상이 바뀌거나 지워져도 기록은 그대로 읽히게 복사해 둔다
    @Column(name = "target_name", length = 100)
    private String targetName;

    // 상세 내용을 JSON 문자열로 저장한다(예: 바뀐 필드의 이전·이후 값)
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    // DB 기본값(CURRENT_TIMESTAMP)이 채운다
    // 작성 시각은 DB 기본값이 채운다. JPA는 쓰지 않고 INSERT 뒤에 읽어 오기만 한다
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // 모든 값을 한 번에 받아 채운다. 만든 뒤에 바꾸는 메서드(setter)를 일부러 두지 않았다
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
