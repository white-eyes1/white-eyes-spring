package com.whiteeyes.admin.stat.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;

/** 일별 통계 저장(FNC-A-030) */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyStatService {

    // 묶을 재료인 시간별 통계
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    // 묶은 결과를 저장하는 일별 통계
    private final ZoneStatDailyRepository zoneStatDailyRepository;

    /**
     * 그날 시간별 통계를 구역별로 묶어 일별 통계로 덮어쓴다(시간별과 같은 계산). 같은 날짜로 다시 실행해도 결과가 같다.
     * 그날 시간별 행 중 하나라도 시연용이면 일별 행도 시연용이다.
     *
     * @param date 묶을 날짜(KST)
     */
    public void aggregateDay(LocalDate date) {
        aggregateDay(date, null);
    }

    /**
     * 정해진 구역만 묶는다. 빠진 집계 보충(FNC-A-031)이 빠진 구역만 다시 계산할 때 쓴다.
     * 정시 작업과 보충이 같은 행을 동시에 저장하다 키가 충돌하지 않게 메서드를 직렬로 실행한다(관리자 앱은 한 프로세스).
     *
     * @param zoneIds 묶을 구역 번호. null이면 그날의 전 구역
     */
    public synchronized void aggregateDay(LocalDate date, Collection<Long> zoneIds) {
        // 그 날짜 00:00 이상, 다음 날 00:00 미만인 시간별 행을 모두 읽는다(반열린 구간이라 경계 시각이 두 날에 겹치지 않는다)
        List<ZoneStatHourly> hours = zoneStatHourlyRepository.findByStatAtGreaterThanEqualAndStatAtLessThan(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        // 구역별로 나눈다. zoneIds가 주어지면 그 구역만 남기고, null이면 전 구역을 대상으로 한다
        Map<Long, List<ZoneStatHourly>> hoursByZone = hours.stream()
                .filter(hour -> zoneIds == null || zoneIds.contains(hour.getZoneId()))
                .collect(Collectors.groupingBy(ZoneStatHourly::getZoneId));

        // 구역마다 따로 저장한다. 한 구역이 실패해도 나머지 구역 집계는 계속한다
        hoursByZone.forEach((zoneId, zoneHours) -> {
            try {
                save(zoneId, date, zoneHours);
            } catch (RuntimeException e) {
                // 실패한 구역은 다음 보충(FNC-A-031)이 빠진 일별 행으로 찾아 다시 채운다
                log.error("일별 통계 저장 실패 zoneId={} statDate={}", zoneId, date, e);
            }
        });
        log.info("일별 통계 저장 statDate={} zones={}", date, hoursByZone.size());
    }

    /** 한 구역의 그날 시간별 행들을 일별 행 하나로 묶어 저장한다. 같은 키의 행이 있으면 덮어쓴다. */
    private void save(Long zoneId, LocalDate date, List<ZoneStatHourly> zoneHours) {
        // 묶을 수 없는 경우(받은 초가 있는 시간별 행이 없음)에는 빈 값이 돌아와 저장하지 않고 경고만 남긴다
        ZoneStatDaily.rollUp(zoneId, date, zoneHours)
                .ifPresentOrElse(
                        zoneStatDailyRepository::save,
                        () -> log.warn("일별 통계 건너뜀: 받은 초가 있는 시간별 통계 없음 zoneId={} statDate={}", zoneId, date));
    }
}
