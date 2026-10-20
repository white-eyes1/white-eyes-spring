/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/level/dto/LevelResponse.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] GET /api/v1/meta/levels 응답 배열의 항목 하나. 예: {"level": 2, "name": "주의", "color": "#F59E0B", "minDensity": 0.7}
 * [왜 DTO를 따로 두나] package-structure.md 3-3: 응답은 항상 DTO로 한다. enum(CongestionLevel)을 그대로 내보내면
 *   JSON 모양이 enum 구현에 묶여서, enum을 고칠 때 API까지 바뀐다.
 * [이름] 필드 이름은 api/public.md 예시와 한 글자도 다르지 않게 맞췄다(level, name, color, minDensity).
 *   record의 필드 이름이 곧 JSON 키가 된다.
 */
package com.whiteeyes.common.level.dto;

import java.math.BigDecimal;

/**
 * 혼잡 단계 하나의 기준(GET /api/v1/meta/levels 응답 항목).
 *
 * @param minDensity 이 단계가 시작되는 밀집도(명/㎡). 정상은 0
 */
// [해석] minDensity를 BigDecimal로 둬서 JSON에 0.7이 그대로 나간다(double이면 계산 과정에 따라 0.7000000001 같은 값이 될 수 있다).
public record LevelResponse(int level, String name, String color, BigDecimal minDensity) {
}
