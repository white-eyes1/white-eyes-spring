-- TODO(#18): 1분 통계 저장(FNC-A-028)에서 정식 V파일을 만들면 이 파일을 지운다.
-- 테스트에서만 실행되는 마이그레이션이다(src/test/resources). 지도 현황(FNC-C-009) 테스트가 zone_stat_minute을 쓰려고 둔다.
-- 정식 번호와 겹치지 않게 큰 번호를 쓴다. 내용은 tables.md 'zone_stat_minute'

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
