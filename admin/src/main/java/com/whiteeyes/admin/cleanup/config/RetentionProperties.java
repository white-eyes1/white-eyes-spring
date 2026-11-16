package com.whiteeyes.admin.cleanup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 오래된 데이터 보관 기간(conventions.md 환경변수). application.yml이 STAT_MINUTE_RETENTION_DAYS·RECORDING_RETENTION_DAYS를 연결한다.
 *
 * @param statMinuteDays 1분 통계 보관 일수(stagg-004)
 * @param recordingDays 녹화 기록 보관 일수(rec-002)
 */
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(@DefaultValue("90") int statMinuteDays, @DefaultValue("30") int recordingDays) {

    // 컴팩트 생성자: 설정 값을 바인딩한 직후 검증한다. 잘못된 값이면 앱이 뜨지 않아 운영 중 데이터가 통째로 지워지는 사고를 막는다.
    public RetentionProperties {
        // 0 이하면 방금 저장한 데이터까지 지워지므로 앱을 띄우지 않는다
        if (statMinuteDays < 1 || recordingDays < 1) {
            throw new IllegalArgumentException("보관 일수는 1 이상이어야 한다");
        }
    }
}
