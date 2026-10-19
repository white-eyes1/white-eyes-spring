/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/stat/service/SecondResult.java
 * 관련 이슈: #16(FNC-A-024)
 *
 * [무엇] 분석 서버가 보낸 '1초 결과 하나'를 서비스 내부 값으로 바꾼 것. 누적기(MinuteAccumulator)에 초 단위로 저장된다.
 * [왜 cameraId·at이 없나] 누적기 쪽에서 이미 (구역, 분)으로 묶고 초(second)를 맵의 키로 쓰기 때문에, 값 안에 또 둘 필요가 없다.
 *   남은 것은 1분 통계 계산(FNC-A-028)에 실제로 쓰이는 값뿐이다: 인원, 방향별 유입·유출, 체류 시간 목록.
 */
package com.whiteeyes.admin.stat.service;

import java.util.List;

/**
 * 분석 서버가 보낸 1초 결과 하나(FNC-A-024).
 */
public record SecondResult(int personCount, DirectionCounts in, DirectionCounts out, List<Double> dwellSec) {

    // [해석] record의 '간결 생성자'. 들어온 리스트를 읽기 전용 복사본으로 바꿔 둔다.
    //        이유: 바깥에서 넘긴 리스트를 나중에 누가 고치면 누적기 안의 값까지 바뀌는 사고를 막으려고.
    //        누적기는 다른 스레드(1분 마감)가 읽는 메모리라, 저장된 값이 절대 안 바뀐다는 보장이 중요하다.
    //        List.copyOf는 null 원소를 거부하는데, DTO에서 이미 @NotNull로 막았으므로 정상 흐름에서는 문제가 없다.
    public SecondResult {
        dwellSec = List.copyOf(dwellSec);
    }
}
