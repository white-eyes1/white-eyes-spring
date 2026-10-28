package com.whiteeyes.common.stat.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whiteeyes.common.stat.entity.ZoneNowCount;

public interface ZoneNowCountRepository extends JpaRepository<ZoneNowCount, Long> {

    /** from 이후(포함)에 찍힌 전체 구역의 현재 인원. 통계 SSE가 5초마다 한 번 읽는다(FNC-C-022). */
    List<ZoneNowCount> findByUpdatedAtGreaterThanEqual(LocalDateTime from);

    /** 구역들의 from 이후(포함)에 찍힌 현재 인원. 요약·지도·캠 목록이 읽는다. */
    List<ZoneNowCount> findByZoneIdInAndUpdatedAtGreaterThanEqual(Collection<Long> zoneIds, LocalDateTime from);
}
