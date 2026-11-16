package com.whiteeyes.common.level.service;

/**
 * 혼잡 기준을 적용할 대상(구역 또는 지역). {@link LevelService#findThresholds}에 넘긴다.
 */
// 혼잡 기준을 적용할 대상(종류 + 번호)을 하나로 묶은 값
public record LevelTarget(Type type, long id) {

    // 대상 종류: 구역 또는 지역
    public enum Type {
        ZONE,
        REGION
    }

    /** 구역(zone.id)을 대상으로 하는 LevelTarget을 만든다. 호출하는 쪽 코드가 읽기 쉽도록 둔 생성 도우미다. */
    public static LevelTarget zone(long zoneId) {
        return new LevelTarget(Type.ZONE, zoneId);
    }

    /** 동·구·시(region.id). */
    public static LevelTarget region(long regionId) {
        return new LevelTarget(Type.REGION, regionId);
    }
}
