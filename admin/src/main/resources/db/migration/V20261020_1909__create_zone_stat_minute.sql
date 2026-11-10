-- 구역별 1분 통계: tables.md 'zone_stat_minute'. 관리자 앱이 1분마다 (구역, 분) 기준으로 넣거나 덮어쓴다(FNC-A-028). 90일만 보관

CREATE TABLE zone_stat_minute (
    zone_id BIGINT NOT NULL COMMENT '구역(zone.id)',
    stat_at DATETIME NOT NULL COMMENT '1분 구간 시작 시각(KST, 초는 0)',
    count_avg DECIMAL(7, 2) NOT NULL COMMENT '1분 평균 인원',
    count_max INT NOT NULL COMMENT '1분 최대 인원',
    count_min INT NOT NULL COMMENT '1분 최저 인원',
    density DECIMAL(8, 4) NOT NULL COMMENT '평균 밀집도(명/㎡)',
    in_n INT NOT NULL DEFAULT 0 COMMENT '북쪽 변을 넘어 들어온 인원 합',
    in_e INT NOT NULL DEFAULT 0 COMMENT '동쪽 변을 넘어 들어온 인원 합',
    in_s INT NOT NULL DEFAULT 0 COMMENT '남쪽 변을 넘어 들어온 인원 합',
    in_w INT NOT NULL DEFAULT 0 COMMENT '서쪽 변을 넘어 들어온 인원 합',
    out_n INT NOT NULL DEFAULT 0 COMMENT '북쪽 변을 넘어 나간 인원 합',
    out_e INT NOT NULL DEFAULT 0 COMMENT '동쪽 변을 넘어 나간 인원 합',
    out_s INT NOT NULL DEFAULT 0 COMMENT '남쪽 변을 넘어 나간 인원 합',
    out_w INT NOT NULL DEFAULT 0 COMMENT '서쪽 변을 넘어 나간 인원 합',
    dwell_sum_sec INT NOT NULL DEFAULT 0 COMMENT '구역을 떠난 사람들의 체류 시간 합(초)',
    dwell_count INT NOT NULL DEFAULT 0 COMMENT '체류 시간을 잰 인원',
    sample_sec SMALLINT NOT NULL DEFAULT 0 COMMENT '결과가 실제로 들어온 초 수(0~60)',
    CONSTRAINT zone_stat_minute_pk PRIMARY KEY (zone_id, stat_at),
    CONSTRAINT zone_stat_minute_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
) ENGINE = InnoDB;

CREATE INDEX zone_stat_minute_ix_stat_at ON zone_stat_minute (stat_at);
