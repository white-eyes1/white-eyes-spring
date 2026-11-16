package com.whiteeyes.common.stat.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class TrendService {

    // 대상 기간의 길이(일)
    private static final int PERIOD_DAYS = 7;
    // 비교 기준 기간까지 거슬러 올라갈 일수. 모두 7의 배수라 대상과 요일 구성이 같다
    private static final int WEEK_AGO_DAYS = 7;
    private static final int MONTH_AGO_DAYS = 28;
    private static final int YEAR_AGO_DAYS = 364;

    // 증감률 계산을 새로 만들지 않고 기간 비교(CompareService)의 전체 증감률을 재사용한다
    private final CompareService compareService;

    /**
     * @param today 오늘(KST). 대상 기간은 어제까지 7일
     * @throws com.whiteeyes.common.region.exception.RegionNotFoundException 없는 지역이면(404)
     * @throws com.whiteeyes.common.region.exception.ZoneNotFoundException 없거나 삭제된 구역이면(404)
     */
    @Transactional(readOnly = true)
    public TrendResponse findTrend(DashboardTargetType targetType, long targetId, LocalDate today) {
        // 오늘은 아직 끝나지 않아 통계가 덜 쌓였으므로 어제까지를 대상 기간의 끝으로 삼는다
        LocalDate yesterday = today.minusDays(1);
        // 대상 기간 = 어제를 포함해 7일. 양끝을 포함하는 기간이라 시작은 6일 전이다
        ComparePeriod target = new ComparePeriod(yesterday.minusDays(PERIOD_DAYS - 1), yesterday);
        // 같은 대상 기간을 7일·28일·364일 앞선 기간과 각각 비교한다
        return new TrendResponse(
                rate(targetType, targetId, target, WEEK_AGO_DAYS),
                rate(targetType, targetId, target, MONTH_AGO_DAYS),
                rate(targetType, targetId, target, YEAR_AGO_DAYS));
    }

    // 기준 기간 데이터가 없어 비교할 칸이 없으면 기간 비교의 전체 증감률이 null이다('-')
    private BigDecimal rate(DashboardTargetType targetType, long targetId, ComparePeriod target, int daysAgo) {
        // 기준 기간 = 대상 기간을 daysAgo만큼 앞으로 옮긴 것. 비정상 기준은 여기서 쓰이지 않으므로(totalRate만 사용) 기본값을 넣는다
        CompareRequest request = new CompareRequest(
                targetType, targetId, target, target.minusDays(daysAgo), CompareRequest.DEFAULT_THRESHOLD_PCT);
        // 168칸 결과는 버리고 전체 증감률만 쓴다. 비교할 칸이 없으면 null이 그대로 화면의 '-'가 된다
        return compareService.compare(request).totalRate();
    }
}
