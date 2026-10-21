package com.whiteeyes.admin.stat.service;

import java.util.List;

/**
 * 분석 서버가 보낸 1초 결과 하나(FNC-A-024).
 */
public record SecondResult(int personCount, DirectionCounts in, DirectionCounts out, List<Double> dwellSec) {

    public SecondResult {
        dwellSec = List.copyOf(dwellSec);
    }
}
