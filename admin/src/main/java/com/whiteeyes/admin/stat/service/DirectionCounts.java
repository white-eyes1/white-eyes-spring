package com.whiteeyes.admin.stat.service;

/**
 * 방향별(N·E·S·W) 인원. zone_stat_minute의 in_n~in_w, out_n~out_w에 대응한다.
 */
public record DirectionCounts(int north, int east, int south, int west) {

    // 합계를 구할 때 시작값(reduce의 identity)으로 쓴다
    public static final DirectionCounts ZERO = new DirectionCounts(0, 0, 0, 0);

    /** 방향별로 더한 새 값을 돌려준다. 이 객체와 other는 바뀌지 않는다. */
    public DirectionCounts plus(DirectionCounts other) {
        return new DirectionCounts(north + other.north, east + other.east, south + other.south, west + other.west);
    }
}
