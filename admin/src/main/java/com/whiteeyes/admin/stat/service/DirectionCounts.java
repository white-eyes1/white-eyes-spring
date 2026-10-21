package com.whiteeyes.admin.stat.service;

/**
 * 방향별(N·E·S·W) 인원. zone_stat_minute의 in_n~in_w, out_n~out_w에 대응한다.
 */
public record DirectionCounts(int north, int east, int south, int west) {

    public static final DirectionCounts ZERO = new DirectionCounts(0, 0, 0, 0);

    public DirectionCounts plus(DirectionCounts other) {
        return new DirectionCounts(
                north + other.north, east + other.east, south + other.south, west + other.west);
    }
}
