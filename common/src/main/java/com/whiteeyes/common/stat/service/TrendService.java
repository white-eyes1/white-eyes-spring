package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whiteeyes.common.stat.dto.ComparePeriod;
import com.whiteeyes.common.stat.dto.CompareRequest;
import com.whiteeyes.common.stat.dto.DashboardTargetType;
import com.whiteeyes.common.stat.dto.TrendResponse;

/**
 * 이용량 증감 요약(FNC-C-019). 어제까지 7일을 7일 전·28일 전·364일 전의 같은 7일과 비교한다(요일을 맞추려고 7의 배수).
 * 증감률은 기간 비교 분석(FNC-C-020)과 같은 방식(같은 요일·시간대끼리 평균 비교)의 전체 증감률이다.
 */
@Service
public class TrendService {

    private static final int PERIOD_DAYS = 7;
    private static final int WEEK_AGO_DAYS = 7;
    private static final int MONTH_AGO_DAYS = 28;
    private static final int YEAR_AGO_DAYS = 364;

    private final CompareService compareService;

    public TrendService(CompareService compareService) {
        this.compareService = compareService;
    }

    /**
     * @param today 오늘(KST). 대상 기간은 어제까지 7일
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public TrendResponse findTrend(DashboardTargetType targetType, long targetId, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        ComparePeriod target = new ComparePeriod(yesterday.minusDays(PERIOD_DAYS - 1), yesterday);
        return new TrendResponse(
                rate(targetType, targetId, target, WEEK_AGO_DAYS),
                rate(targetType, targetId, target, MONTH_AGO_DAYS),
                rate(targetType, targetId, target, YEAR_AGO_DAYS));
    }

    // 기준 기간 데이터가 없어 비교할 칸이 없으면 기간 비교의 전체 증감률이 null이다('-')
    private BigDecimal rate(DashboardTargetType targetType, long targetId, ComparePeriod target, int daysAgo) {
        CompareRequest request = new CompareRequest(
                targetType, targetId, target, target.minusDays(daysAgo), CompareRequest.DEFAULT_THRESHOLD_PCT);
        return compareService.compare(request).totalRate();
    }
}
