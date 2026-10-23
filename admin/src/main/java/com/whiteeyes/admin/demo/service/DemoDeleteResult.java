package com.whiteeyes.admin.demo.service;

/**
 * 시연용 데이터 삭제 결과(FNC-A-052 정상 결과 '삭제 건수').
 *
 * @param hourly zone_stat_hourly에서 지운 건수
 * @param daily zone_stat_daily에서 지운 건수
 */
public record DemoDeleteResult(long hourly, long daily) {

    public long total() {
        return hourly + daily;
    }
}
