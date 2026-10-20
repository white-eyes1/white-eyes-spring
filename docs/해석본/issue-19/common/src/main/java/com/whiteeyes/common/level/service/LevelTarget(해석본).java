/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/level/service/LevelTarget.java
 * 관련 이슈: #19(FNC-C-025)
 *
 * [무엇] "어느 구역 또는 어느 지역의 기준인가"를 담는 값. LevelService.findThresholds에 넘긴다.
 * [왜 필요한가] 명세 처리 순서 2: 기준값을 돌려주는 함수는 '구역(또는 지역)을 받아 적용 기준을 돌려주는' 형태로 하나만 둔다.
 *   2순위 기능 '구역별 기준값 조정'(backlog-p2.md 8번)이 들어오면, 구역마다 다른 기준을 쓸 수 있게 된다.
 *   그때 이 대상 정보를 보고 기준을 고르면 되므로, 지금부터 함수가 대상을 받게 만들어 둔다(사용자와 합의한 결정 1).
 * [염두] 지금은 대상이 무엇이든 공통 기준을 돌려주므로 이 값은 쓰이지 않는다. 그래도 미리 받게 해 두면, 나중에 기능이
 *   들어올 때 이 함수를 부르는 #20·#21·#38 코드를 하나도 고치지 않아도 된다.
 */
package com.whiteeyes.common.level.service;

/**
 * 혼잡 기준을 적용할 대상(구역 또는 지역). {@link LevelService#findThresholds}에 넘긴다.
 */
// [해석] record: 값만 담는 불변 객체를 한 줄로 만든다. equals·hashCode·toString이 자동으로 생긴다.
//        id를 Long이 아니라 long으로 둔 이유: 대상 없는 기준 조회는 없으므로 null이 들어올 일이 없게 막는다.
public record LevelTarget(Type type, long id) {

    // [해석] 구역(zone)과 지역(region: 동·구·시)을 구분한다. 2순위 기능에서도 구역만 개별 기준을 갖고,
    //        지역은 계속 공통 기준을 쓴다(backlog-p2: 지역 색이 구역 색과 어긋나지 않게).
    public enum Type {
        ZONE,
        REGION
    }

    // [해석] new LevelTarget(Type.ZONE, 3) 대신 LevelTarget.zone(3)으로 쓰게 하는 정적 생성 메서드.
    //        부르는 코드가 짧고, 구역 id를 지역 자리에 넣는 실수를 이름만 보고도 알아챌 수 있다.
    public static LevelTarget zone(long zoneId) {
        return new LevelTarget(Type.ZONE, zoneId);
    }

    /** 동·구·시(region.id). */
    public static LevelTarget region(long regionId) {
        return new LevelTarget(Type.REGION, regionId);
    }
}
