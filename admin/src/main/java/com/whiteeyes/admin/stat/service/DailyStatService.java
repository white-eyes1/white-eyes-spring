package com.whiteeyes.admin.stat.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatDaily;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.repository.ZoneStatDailyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;

/** 일별 통계 저장(FNC-A-030) */
@Service
public class DailyStatService {

    private static final Logger log = LoggerFactory.getLogger(DailyStatService.class);

    private final ZoneStatHourlyRepository zoneStatHourlyRepository;
    private final ZoneStatDailyRepository zoneStatDailyRepository;

    public DailyStatService(
            ZoneStatHourlyRepository zoneStatHourlyRepository, ZoneStatDailyRepository zoneStatDailyRepository) {
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
        this.zoneStatDailyRepository = zoneStatDailyRepository;
    }

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
        List<ZoneStatHourly> hours = zoneStatHourlyRepository.findByStatAtGreaterThanEqualAndStatAtLessThan(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        Map<Long, List<ZoneStatHourly>> hoursByZone = hours.stream()
                .filter(hour -> zoneIds == null || zoneIds.contains(hour.getZoneId()))
                .collect(Collectors.groupingBy(ZoneStatHourly::getZoneId));

        hoursByZone.forEach((zoneId, zoneHours) -> {
            try {
                save(zoneId, date, zoneHours);
            } catch (RuntimeException e) {
                log.error("일별 통계 저장 실패 zoneId={} statDate={}", zoneId, date, e);
            }
        });
        log.info("일별 통계 저장 statDate={} zones={}", date, hoursByZone.size());
    }

    private void save(Long zoneId, LocalDate date, List<ZoneStatHourly> zoneHours) {
        List<StatValues> rows = zoneHours.stream().map(ZoneStatHourly::values).toList();
        StatValues rolledUp = StatRollupCalculator.rollUp(rows).orElse(null);
        if (rolledUp == null) {
            log.warn("일별 통계 건너뜀: 받은 초가 있는 시간별 통계 없음 zoneId={} statDate={}", zoneId, date);
            return;
        }
        boolean demo = zoneHours.stream().anyMatch(ZoneStatHourly::isDemo);
        zoneStatDailyRepository.save(new ZoneStatDaily(zoneId, date, rolledUp, demo));
    }
}
