package com.whiteeyes.common.stat.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 구역별 현재 인원(가장 최근 초의 인원)을 내는 쪽(FNC-A-054, 결정 0018). 관리자 앱만 구현 빈을 둔다(메모리 누적기를 읽음).
 * 사용자 앱에는 빈이 없어 currentCount는 null이고 event: now 도 보내지 않는다 — 이 모듈의 읽는 쪽은
 * {@code ObjectProvider<NowCountSource>}로 받아 빈이 없으면 건너뛴다.
 * 1분 통계·이벤트·집계·엑셀은 이 값을 읽지 않는다.
 */
public interface NowCountSource {

    /** 구역별 신선한(NOW_COUNT_FRESH_SEC 이내에 찍힌) 현재 인원. 없는 구역은 빠진다. */
    Map<Long, NowCount> findFresh(Collection<Long> zoneIds, LocalDateTime now);

    /** 전체 구역 중 신선한 현재 인원. 통계 SSE가 5초마다 한 번 부른다(FNC-C-022). */
    List<NowCount> findAllFresh(LocalDateTime now);

    /** 신선한 구역들의 현재 인원 합. 신선한 값이 하나도 없으면 빈 값(화면은 1분 평균으로 되돌린다). */
    default OptionalInt sumFresh(Collection<Long> zoneIds, LocalDateTime now) {
        if (zoneIds.isEmpty()) {
            return OptionalInt.empty();
        }
        Map<Long, NowCount> found = findFresh(zoneIds, now);
        if (found.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(found.values().stream().mapToInt(NowCount::personCount).sum());
    }

    /**
     * 구역 하나의 현재 인원.
     *
     * @param at 그 인원이 찍힌 초(KST)
     */
    record NowCount(long zoneId, int personCount, LocalDateTime at) {}
}
