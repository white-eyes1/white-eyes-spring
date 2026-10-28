package com.whiteeyes.common.stat.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구역별 현재 인원(tables.md 'zone_now_count'). 관리자 앱이 5초마다 덮어쓴다(FNC-A-054).
 * 1분 통계가 아니므로 통계·이벤트·집계·엑셀은 읽지 않는다.
 */
@Entity
@Table(name = "zone_now_count")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneNowCount {

    @Id
    @Column(name = "zone_id")
    private Long zoneId;

    /** 가장 최근 초의 인원. */
    @Column(name = "person_count", nullable = false)
    private int personCount;

    /** 이 인원이 찍힌 초 시각(KST). */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
