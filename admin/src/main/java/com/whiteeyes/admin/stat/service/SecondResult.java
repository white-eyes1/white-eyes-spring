package com.whiteeyes.admin.stat.service;

import java.util.List;

/**
 * 분석 서버가 보낸 1초 결과 하나(FNC-A-024).
 */
public record SecondResult(int personCount, DirectionCounts in, DirectionCounts out, List<Double> dwellSec) {

    // 불변 복사본을 담아, 호출한 쪽이 나중에 원본 목록을 고쳐도 누적기 안의 값이 바뀌지 않게 한다
    public SecondResult {
        dwellSec = List.copyOf(dwellSec);
    }
}
