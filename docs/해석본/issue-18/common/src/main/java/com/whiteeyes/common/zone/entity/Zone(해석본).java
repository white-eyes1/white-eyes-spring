/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/zone/entity/Zone.java
 * 관련 이슈: #18(FNC-A-028). 임시 코드, 정식 코드는 #10(FNC-A-014 구역 등록)
 *
 * [무엇] zone 테이블의 엔티티. 지금은 id와 면적(area_m2)만 있다.
 * [왜 임시인가] 1분 통계의 밀집도 = 평균 인원 ÷ 구역 면적인데, 구역을 만드는 #10이 아직 머지되지 않아
 *   Zone 엔티티가 어느 브랜치에도 없었다. 빌드와 테스트를 위해 꼭 필요한 칼럼만 먼저 둔다(사용자와 합의한 결정 2).
 * [염두] 칼럼 이름·타입은 tables.md 'zone'과 똑같이 맞췄다. 그래야 #10의 정식 Zone으로 바꿀 때
 *   이 파일만 갈아 끼우면 되고, 이 엔티티를 쓰는 코드(ZoneQueryService)는 거의 그대로 둘 수 있다.
 *   deleted_at은 처음엔 넣으려 했지만 뺐다. 면적은 구역이 삭제됐는지와 상관없이 읽으면 되고,
 *   삭제된 캠의 결과는 받는 단계(#16)에서 이미 걸러져서 쓰는 곳이 없기 때문(필요 없는 칼럼은 두지 않음).
 */
package com.whiteeyes.common.zone.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// TODO(#10): 구역 등록(FNC-A-014)에서 tables.md 'zone'의 전체 칼럼으로 정식으로 만든다.
// 지금은 FNC-A-028의 밀집도 계산에 필요한 면적만 둔 임시 코드
// [해석] common 모듈에 둔 이유: zone은 두 앱(user·admin)이 함께 읽는 테이블이라 package-structure.md 규칙상 common에 둔다.
@Entity
@Table(name = "zone")
// [해석] getter만 두고 setter는 두지 않는다(code-conventions: 엔티티에 setter 금지). 값은 #10의 등록·수정 메서드로만 바뀌어야 한다.
@Getter
// [해석] JPA가 DB에서 읽어 객체를 만들 때 쓰는 기본 생성자. PROTECTED로 막아 다른 코드가 빈 Zone을 만들지 못하게 한다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {

    // [해석] tables.md: id BIGINT AUTO_INCREMENT → IDENTITY 전략(MySQL의 AUTO_INCREMENT를 그대로 씀).
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // [해석] tables.md: area_m2 DECIMAL(10,2) NOT NULL. 돈·면적처럼 정확해야 하는 소수는 double 대신 BigDecimal을 쓴다
    //        (double은 0.1 같은 값을 정확히 못 담아 나눗셈 결과가 미세하게 어긋난다).
    @Column(name = "area_m2", nullable = false, precision = 10, scale = 2)
    private BigDecimal areaM2;
}
