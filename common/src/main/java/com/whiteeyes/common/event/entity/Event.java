package com.whiteeyes.common.event.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/**
 * 이벤트(tables.md 'event'). 혼잡 이벤트 판정(FNC-A-033), 급증 이벤트 판정(FNC-A-034), 캠 이상 감지(FNC-A-035)가
 * 만들고 갱신·종료한다.
 *
 * <p>처리 칼럼(action_code·action_note·handled_by·handled_at)은 이벤트 처리 기능을 만들 때 매핑한다.
 * created_at·updated_at은 DB 기본값이 채운다.
 */
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private EventType type;

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

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "peak_level")
    private Integer peakLevel;

    @Column(name = "peak_value", precision = 10, scale = 4)
    private BigDecimal peakValue;

    @Column(name = "peak_count")
    private Integer peakCount;

    /** 기준 아래로 내려간 시각. 다시 기준을 넘으면 null. */
    @Column(name = "below_since")
    private LocalDateTime belowSince;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "threshold_snapshot", nullable = false)
    private String thresholdSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

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
        return new Event(EventType.SURGE, zoneId, null, startedAt, null, ratePct, peakCount, thresholdSnapshot);
    }

    /** 열려 있는 동안 더 오른 값만 최고 값으로 남긴다(단계·값·인원을 각각 최댓값으로). */
    public void raisePeak(int level, BigDecimal value, int count) {
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

    public void close(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }
}
