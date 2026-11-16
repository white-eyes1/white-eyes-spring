package com.whiteeyes.common.event.entity;

import java.math.BigDecimal;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 이벤트(tables.md 'event'). 혼잡 이벤트 판정(FNC-A-033), 급증 이벤트 판정(FNC-A-034), 캠 이상 감지(FNC-A-035)가
 * 만들고 갱신·종료한다.
 *
 * <p>처리 칼럼(action_code·action_note·handled_by·handled_at)은 처리 상태 변경(FNC-A-038)이
 * {@link #markChecked}·{@link #resolve}로 바꾸고 상세 조회(FNC-A-037)가 읽는다. created_at·updated_at은 DB 기본값이 채운다.
 */
// JPA 엔티티. event 테이블 한 행 = 이상 상황 하나(혼잡·급증·캠 이상). 열려 있는 동안(endedAt이 null) 갱신되다가 종료 시각이 채워지면 닫힌다
@Entity
@Table(name = "event")
@Getter
// JPA 전용 기본 생성자는 숨기고, 만들 때는 openCongestion·openSurge·openCamFault 정적 메서드를 쓰게 한다
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    // 기본키. DB 자동 증가 값
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이벤트 종류(혼잡·급증·캠 이상). 이름 문자열로 저장한다
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private EventType type;

    // 이벤트가 난 구역 번호. 구역 엔티티를 직접 들지 않고 번호만 둔다
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    /** 캠 이상일 때만 값이 있다. */
    @Column(name = "camera_id")
    private Long cameraId;

    /** 발생 시각(판정한 1분 통계의 시각). */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** 종료 시각. 열려 있으면 null. */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    // 최고 혼잡 단계. 혼잡 이벤트에만 값이 있고(급증·캠 이상은 null) DB는 TINYINT라 같은 타입으로 매핑한다
    @JdbcTypeCode(SqlTypes.TINYINT)
    // 이벤트가 열려 있는 동안 관측된 최고 단계. 혼잡에서만 쓴다
    @Column(name = "peak_level")
    private Integer peakLevel;

    // 최고 값. 혼잡이면 최고 밀도, 급증이면 최고 증가율(%). 소수 4자리까지 정확히 저장해야 해서 BigDecimal을 쓴다
    @Column(name = "peak_value", precision = 10, scale = 4)
    private BigDecimal peakValue;

    // 최고 인원 수. 혼잡·급증에서 쓴다
    @Column(name = "peak_count")
    private Integer peakCount;

    /** 기준 아래로 내려간 시각. 다시 기준을 넘으면 null. */
    @Column(name = "below_since")
    private LocalDateTime belowSince;

    // 발생 당시의 기준값을 JSON 문자열로 저장한다. 나중에 기준을 바꿔도 "그때 왜 이벤트가 났는지"를 설명할 수 있도록 값을 복사해 둔다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "threshold_snapshot", nullable = false)
    private String thresholdSnapshot;

    // 처리 상태(미확인 → 확인 중 → 조치완료). 이벤트 종료 여부(endedAt)와는 별개다
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    /** 조치 항목 코드(ONSITE_OK·STAFF·FALSE_ALARM·CAM_CHECK·OTHER). 조치완료일 때만 값이 있다. */
    @Column(name = "action_code", length = 20)
    private String actionCode;

    // 조치 메모. 조치 항목이 OTHER일 때는 필수이고 나머지는 선택이다
    @Column(name = "action_note", length = 200)
    private String actionNote;

    /** 마지막으로 처리 상태를 바꾼 관리자(admin_account.id). admin 전용 테이블이라 연관관계 없이 번호만 둔다. */
    @Column(name = "handled_by")
    private Long handledBy;

    // 마지막으로 처리 상태를 바꾼 시각
    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    // 정적 생성 메서드 세 개가 공통으로 쓰는 생성자. 종류에 해당하지 않는 칸은 null로 받는다
    private Event(
            EventType type,
            Long zoneId,
            Long cameraId,
            LocalDateTime startedAt,
            Integer peakLevel,
            BigDecimal peakValue,
            Integer peakCount,
            String thresholdSnapshot) {
        this.type = type;
        this.zoneId = zoneId;
        this.cameraId = cameraId;
        this.startedAt = startedAt;
        this.peakLevel = peakLevel;
        this.peakValue = peakValue;
        this.peakCount = peakCount;
        this.thresholdSnapshot = thresholdSnapshot;
        // 모든 새 이벤트는 사람이 아직 보지 않은 상태로 시작한다
        this.status = EventStatus.UNCHECKED;
    }

    /**
     * 새 혼잡 이벤트(미확인). 대상 캠은 없다.
     *
     * @param startedAt 판정한 1분 통계의 시각
     * @param thresholdSnapshot 발생 당시 기준값 JSON({warnDensity, dangerDensity})
     */
    public static Event openCongestion(
            Long zoneId,
            LocalDateTime startedAt,
            int peakLevel,
            BigDecimal peakValue,
            int peakCount,
            String thresholdSnapshot) {
        // 혼잡은 구역 단위 이벤트라 camera는 null이고 밀도·인원·단계를 모두 채운다
        return new Event(
                EventType.CONGESTION, zoneId, null, startedAt, peakLevel, peakValue, peakCount, thresholdSnapshot);
    }

    /**
     * 새 캠 이상 이벤트(미확인). 최고 단계·값·인원은 캠 이상에 없어 비워 둔다.
     *
     * @param zoneId 캠이 있는 구역
     * @param startedAt 이상을 발견한 시각
     * @param thresholdSnapshot 발생 당시 기준값 JSON({timeoutSec})
     */
    public static Event openCamFault(Long zoneId, Long cameraId, LocalDateTime startedAt, String thresholdSnapshot) {
        // 캠 이상은 어느 캠이 문제인지가 중요하므로 cameraId를 넣고, 혼잡 관련 칸은 비운다
        return new Event(EventType.CAM_FAULT, zoneId, cameraId, startedAt, null, null, null, thresholdSnapshot);
    }

    /**
     * 새 급증 이벤트(미확인). 대상 캠과 최고 혼잡 단계는 없다.
     *
     * @param startedAt 판정한 1분 통계의 시각
     * @param ratePct 증가율(%)
     * @param thresholdSnapshot 발생 당시 기준값 JSON({window, rate, minDensity})
     */
    public static Event openSurge(
            Long zoneId, LocalDateTime startedAt, BigDecimal ratePct, int peakCount, String thresholdSnapshot) {
        // 급증은 peakValue 칸에 증가율을 담는다(혼잡의 밀도 칸을 재사용). 단계는 없다
        return new Event(EventType.SURGE, zoneId, null, startedAt, null, ratePct, peakCount, thresholdSnapshot);
    }

    /** 열려 있는 동안 더 오른 값만 최고 값으로 남긴다(단계·값·인원을 각각 최댓값으로). */
    public void raisePeak(int level, BigDecimal value, int count) {
        // 값·단계·인원을 각각 따로 비교해 더 큰 것만 남긴다. 처음 값(null)이면 무조건 채운다
        if (peakLevel == null || level > peakLevel) {
            this.peakLevel = level;
        }
        if (peakValue == null || value.compareTo(peakValue) > 0) {
            this.peakValue = value;
        }
        if (peakCount == null || count > peakCount) {
            this.peakCount = count;
        }
    }

    /** 급증 이벤트가 열려 있는 동안 더 오른 증가율(%)·인원만 최고 값으로 남긴다. 최고 단계는 없다. */
    public void raiseSurgePeak(BigDecimal ratePct, int count) {
        if (peakValue == null || ratePct.compareTo(peakValue) > 0) {
            this.peakValue = ratePct;
        }
        if (peakCount == null || count > peakCount) {
            this.peakCount = count;
        }
    }

    /** 다시 기준을 넘었으므로 내려간 기록을 지운다. */
    public void clearBelowSince() {
        this.belowSince = null;
    }

    /** 기준 아래로 처음 내려간 시각을 남긴다. 이미 있으면 처음 시각을 유지한다. */
    public void markBelow(LocalDateTime at) {
        if (belowSince == null) {
            this.belowSince = at;
        }
    }

    /** 종료 시각을 채워 이벤트를 닫는다. 닫힌 이벤트는 열린 이벤트 조회에서 빠진다. */
    public void close(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    /** 미확인 이벤트를 확인 중으로 바꾼다(FNC-A-038). 앞으로만 바꿀 수 있는지는 부르는 쪽이 {@link EventStatus#canChangeTo}로 본다. */
    public void markChecked(Long adminId, LocalDateTime handledAt) {
        this.status = EventStatus.CHECKED;
        this.handledBy = adminId;
        this.handledAt = handledAt;
    }

    /** 조치완료로 바꾸고 조치 항목·메모를 남긴다(FNC-A-038). 메모가 없으면 null. */
    public void resolve(EventActionCode actionCode, String actionNote, Long adminId, LocalDateTime handledAt) {
        this.status = EventStatus.RESOLVED;
        this.actionCode = actionCode.name();
        this.actionNote = actionNote;
        this.handledBy = adminId;
        this.handledAt = handledAt;
    }
}
