-- 구역별 현재 인원: tables.md 'zone_now_count'. 관리자 앱이 5초마다 구역당 1행을 덮어쓴다(FNC-A-054). 이력은 남기지 않고 통계에 쓰지 않는다

CREATE TABLE zone_now_count (
    zone_id BIGINT NOT NULL COMMENT '구역(zone.id)',
    person_count INT NOT NULL COMMENT '가장 최근 초의 인원',
    updated_at DATETIME(3) NOT NULL COMMENT '이 인원이 찍힌 초 시각(KST)',
    CONSTRAINT zone_now_count_pk PRIMARY KEY (zone_id),
    CONSTRAINT zone_now_count_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id) ON DELETE CASCADE
) ENGINE = InnoDB;
