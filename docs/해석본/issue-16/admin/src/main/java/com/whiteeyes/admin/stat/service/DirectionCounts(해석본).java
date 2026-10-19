/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/DirectionCounts.java
 * 관련 이슈: #16(FNC-A-024)
 *
 * [무엇] 방향별(북·동·남·서) 인원 4개를 묶은 값 객체. zone_stat_minute의 in_n~in_w, out_n~out_w에 대응한다.
 * [왜 DTO의 DirectionCount를 그대로 안 쓰나] DTO는 '외부 요청의 모양'이고(검증 어노테이션, JSON 키 매핑이 붙어 있음),
 *   이것은 '서비스 안에서 계산에 쓰는 값'이다. 둘을 나누면 API 계약이 바뀌어도 누적·계산 코드가 흔들리지 않는다
 *   (package-structure 3-3 "DTO ↔ 엔티티 변환은 service에서", 계층 분리).
 * [염두에 둔 것] 값이 null일 수 없도록 원시형 int를 쓴다. DTO 검증(@NotNull)을 통과한 뒤에만 만들어지기 때문이다.
 */
package com.whiteeyes.admin.stat.service;

/**
 * 방향별(N·E·S·W) 인원. zone_stat_minute의 in_n~in_w, out_n~out_w에 대응한다.
 */
public record DirectionCounts(int north, int east, int south, int west) {

    // [해석] 더하기의 시작값(0). 1분 동안의 합계를 reduce로 구할 때 쓴다. 결과가 하나도 없어도 null이 아니라 0이 나온다.
    public static final DirectionCounts ZERO = new DirectionCounts(0, 0, 0, 0);

    // [해석] 자기 자신을 바꾸지 않고 '더한 새 객체'를 돌려준다(record는 불변). 여러 스레드가 읽어도 안전하고,
    //        stream().reduce(ZERO, DirectionCounts::plus)처럼 한 줄로 합계를 낼 수 있다.
    public DirectionCounts plus(DirectionCounts other) {
        return new DirectionCounts(
                north + other.north, east + other.east, south + other.south, west + other.west);
    }
}
