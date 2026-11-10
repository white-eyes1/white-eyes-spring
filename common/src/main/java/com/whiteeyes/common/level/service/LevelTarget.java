package com.whiteeyes.common.level.service;

/**
 * 혼잡 기준을 적용할 대상(구역 또는 지역). {@link LevelService#findThresholds}에 넘긴다.
 */
public record LevelTarget(Type type, long id) {

    public enum Type {
        ZONE,
        REGION
    }

    public static LevelTarget zone(long zoneId) {
        return new LevelTarget(Type.ZONE, zoneId);
    }

    /** 동·구·시(region.id). */
    public static LevelTarget region(long regionId) {
        return new LevelTarget(Type.REGION, regionId);
    }
}
