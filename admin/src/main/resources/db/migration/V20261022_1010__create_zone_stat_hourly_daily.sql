-- 구역별 시간별·일별 통계: tables.md 'zone_stat_hourly', 'zone_stat_daily'.
-- 관리자 앱이 정시 직후(FNC-A-029)·자정 직후(FNC-A-030) 하위 통계를 묶어 (구역, 시각/날짜) 기준으로 덮어쓴다

CREATE TABLE zone_stat_hourly (
    zone_id BIGINT NOT NULL COMMENT '구역(zone.id)',
    stat_at DATETIME NOT NULL COMMENT '1시간 구간 시작 시각(KST, 정시)',
    count_avg DECIMAL(7, 2) NOT NULL COMMENT '1시간 평균 인원(sample_sec 가중 평균)',
    count_max INT NOT NULL COMMENT '1시간 최대 인원',
    count_min INT NOT NULL COMMENT '1시간 최저 인원',
    density DECIMAL(8, 4) NOT NULL COMMENT '평균 밀집도(명/㎡, sample_sec 가중 평균)',
    in_n INT NOT NULL DEFAULT 0 COMMENT '북쪽 변을 넘어 들어온 인원 합',
    in_e INT NOT NULL DEFAULT 0 COMMENT '동쪽 변을 넘어 들어온 인원 합',
    in_s INT NOT NULL DEFAULT 0 COMMENT '남쪽 변을 넘어 들어온 인원 합',
    in_w INT NOT NULL DEFAULT 0 COMMENT '서쪽 변을 넘어 들어온 인원 합',
    out_n INT NOT NULL DEFAULT 0 COMMENT '북쪽 변을 넘어 나간 인원 합',
    out_e INT NOT NULL DEFAULT 0 COMMENT '동쪽 변을 넘어 나간 인원 합',
    out_s INT NOT NULL DEFAULT 0 COMMENT '남쪽 변을 넘어 나간 인원 합',
    out_w INT NOT NULL DEFAULT 0 COMMENT '서쪽 변을 넘어 나간 인원 합',
    dwell_sum_sec BIGINT NOT NULL DEFAULT 0 COMMENT '구역을 떠난 사람들의 체류 시간 합(초)',
    dwell_count INT NOT NULL DEFAULT 0 COMMENT '체류 시간을 잰 인원',
    sample_sec INT NOT NULL DEFAULT 0 COMMENT '결과가 실제로 들어온 초 수(0~3600)',
    is_demo TINYINT NOT NULL DEFAULT 0 COMMENT '1이면 생성 스크립트로 넣은 시연용 데이터',
    CONSTRAINT zone_stat_hourly_pk PRIMARY KEY (zone_id, stat_at),
    CONSTRAINT zone_stat_hourly_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
) ENGINE = InnoDB;

CREATE INDEX zone_stat_hourly_ix_stat_at ON zone_stat_hourly (stat_at);
CREATE INDEX zone_stat_hourly_ix_is_demo ON zone_stat_hourly (is_demo);

CREATE TABLE zone_stat_daily (
    zone_id BIGINT NOT NULL COMMENT '구역(zone.id)',
    stat_date DATE NOT NULL COMMENT '집계 날짜(KST)',
    count_avg DECIMAL(7, 2) NOT NULL COMMENT '하루 평균 인원(sample_sec 가중 평균)',
    count_max INT NOT NULL COMMENT '하루 최대 인원',
    count_min INT NOT NULL COMMENT '하루 최저 인원',
    density DECIMAL(8, 4) NOT NULL COMMENT '평균 밀집도(명/㎡, sample_sec 가중 평균)',
    in_n INT NOT NULL DEFAULT 0 COMMENT '북쪽 변을 넘어 들어온 인원 합',
    in_e INT NOT NULL DEFAULT 0 COMMENT '동쪽 변을 넘어 들어온 인원 합',
    in_s INT NOT NULL DEFAULT 0 COMMENT '남쪽 변을 넘어 들어온 인원 합',
    in_w INT NOT NULL DEFAULT 0 COMMENT '서쪽 변을 넘어 들어온 인원 합',
    out_n INT NOT NULL DEFAULT 0 COMMENT '북쪽 변을 넘어 나간 인원 합',
    out_e INT NOT NULL DEFAULT 0 COMMENT '동쪽 변을 넘어 나간 인원 합',
    out_s INT NOT NULL DEFAULT 0 COMMENT '남쪽 변을 넘어 나간 인원 합',
    out_w INT NOT NULL DEFAULT 0 COMMENT '서쪽 변을 넘어 나간 인원 합',
    dwell_sum_sec BIGINT NOT NULL DEFAULT 0 COMMENT '구역을 떠난 사람들의 체류 시간 합(초)',
    dwell_count INT NOT NULL DEFAULT 0 COMMENT '체류 시간을 잰 인원',
    sample_sec INT NOT NULL DEFAULT 0 COMMENT '결과가 실제로 들어온 초 수(0~86400)',
    is_demo TINYINT NOT NULL DEFAULT 0 COMMENT '1이면 생성 스크립트로 넣은 시연용 데이터',
    CONSTRAINT zone_stat_daily_pk PRIMARY KEY (zone_id, stat_date),
    CONSTRAINT zone_stat_daily_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
) ENGINE = InnoDB;

CREATE INDEX zone_stat_daily_ix_stat_date ON zone_stat_daily (stat_date);
CREATE INDEX zone_stat_daily_ix_is_demo ON zone_stat_daily (is_demo);
