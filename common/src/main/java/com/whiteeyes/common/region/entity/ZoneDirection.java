package com.whiteeyes.common.region.entity;

/** 구역 이웃의 방향(tables.md zone_neighbor.direction: N / E / S / W). */
public enum ZoneDirection {
    N,
    E,
    S,
    W;

    /** 반대쪽 방향. A의 동쪽 이웃이 B면 B의 서쪽 이웃이 A다(FNC-A-016). */
    public ZoneDirection opposite() {
        return switch (this) {
            case N -> S;
            case E -> W;
            case S -> N;
            case W -> E;
        };
    }
}
