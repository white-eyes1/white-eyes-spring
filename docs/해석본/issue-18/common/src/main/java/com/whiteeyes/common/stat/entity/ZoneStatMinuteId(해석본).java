/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/stat/entity/ZoneStatMinuteId.java
 * 관련 이슈: #18(FNC-A-028)
 *
 * [무엇] zone_stat_minute 테이블의 기본 키(zone_id, stat_at) 두 칸을 묶은 클래스.
 * [왜] tables.md의 PK가 칼럼 두 개짜리(복합 키)다. "구역 3의 14:03 통계"처럼 (구역, 분)이 행 하나를 가리킨다.
 *   JPA에서 복합 키는 키 칼럼들을 담은 별도 클래스가 필요하다. 이 클래스는 @IdClass 방식으로 쓴다
 *   (엔티티에는 zoneId·statAt 필드를 그대로 두고, 키 클래스는 findById(new ZoneStatMinuteId(...))에만 씀).
 */
package com.whiteeyes.common.stat.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * zone_stat_minute의 복합 키(zone_id, stat_at).
 */
@Getter
// [해석] JPA 규칙: 키 클래스는 equals·hashCode를 반드시 구현해야 한다. 두 키가 '같은 행'인지 값으로 비교하기 때문.
@EqualsAndHashCode
// [해석] JPA가 쓰는 기본 생성자는 PROTECTED로, 코드에서 쓰는 생성자는 (zoneId, statAt) 두 값을 받는 것으로 둔다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
// [해석] Serializable: JPA 규칙상 키 클래스가 구현해야 하는 인터페이스.
public class ZoneStatMinuteId implements Serializable {

    // [해석] 필드 이름과 타입이 엔티티(ZoneStatMinute)의 @Id 필드와 정확히 같아야 @IdClass가 짝을 맞춘다.
    private Long zoneId;
    private LocalDateTime statAt;
}
