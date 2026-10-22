package com.whiteeyes.admin.stat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.whiteeyes.common.stat.entity.StatValues;
import com.whiteeyes.common.stat.entity.ZoneStatHourly;
import com.whiteeyes.common.stat.entity.ZoneStatMinute;
import com.whiteeyes.common.stat.repository.ZoneStatHourlyRepository;
import com.whiteeyes.common.stat.repository.ZoneStatMinuteRepository;
import com.whiteeyes.common.stat.service.StatRollupCalculator;

/** 시간별 통계 저장(FNC-A-029) */
@Service
public class HourlyStatService {

    private static final Logger log = LoggerFactory.getLogger(HourlyStatService.class);

    private final ZoneStatMinuteRepository zoneStatMinuteRepository;
    private final ZoneStatHourlyRepository zoneStatHourlyRepository;

    public HourlyStatService(
            ZoneStatMinuteRepository zoneStatMinuteRepository, ZoneStatHourlyRepository zoneStatHourlyRepository) {
        this.zoneStatMinuteRepository = zoneStatMinuteRepository;
        this.zoneStatHourlyRepository = zoneStatHourlyRepository;
    }

    /**
     * 그 1시간의 1분 통계를 구역별로 묶어 시간별 통계로 덮어쓴다. 같은 시각으로 다시 실행해도 결과가 같다.
     * 1분 행이 없는 구역은 행을 만들지 않는다. 한 구역이 실패해도 나머지는 계속하고, 빠진 행은 보충(FNC-A-031)이 채운다.
     *
     * @param hourStart 묶을 1시간의 시작 시각(KST, 정시)
     */
    public void aggregateHour(LocalDateTime hourStart) {
        List<ZoneStatMinute> minutes = zoneStatMinuteRepository
                .findByStatAtGreaterThanEqualAndStatAtLessThan(hourStart, hourStart.plusHours(1));
        Map<Long, List<ZoneStatMinute>> minutesByZone =
                minutes.stream().collect(Collectors.groupingBy(ZoneStatMinute::getZoneId));

        minutesByZone.forEach((zoneId, zoneMinutes) -> {
            try {
                save(zoneId, hourStart, zoneMinutes);
            } catch (RuntimeException e) {
                log.error("시간별 통계 저장 실패 zoneId={} statAt={}", zoneId, hourStart, e);
            }
        });
        log.info("시간별 통계 저장 statAt={} zones={}", hourStart, minutesByZone.size());
    }

    private void save(Long zoneId, LocalDateTime hourStart, List<ZoneStatMinute> zoneMinutes) {
        List<StatValues> rows = zoneMinutes.stream().map(StatRollupCalculator::of).toList();
        StatValues rolledUp = StatRollupCalculator.rollUp(rows).orElse(null);
        if (rolledUp == null) {
            log.warn("시간별 통계 건너뜀: 받은 초가 있는 1분 통계 없음 zoneId={} statAt={}", zoneId, hourStart);
            return;
        }
        // 관리자 앱이 묶어 만든 행은 시연용이 아니다(FNC-A-029 처리 순서 3)
        zoneStatHourlyRepository.save(new ZoneStatHourly(zoneId, hourStart, rolledUp, false));
    }
}
