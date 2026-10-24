-- zone_stat_hourly·zone_stat_daily의 is_demo를 tables.md(TINYINT, 길이 1)와 맞춘다.
-- V20261022_1010이 TINYINT로 만들어 MySQL 드라이버가 숫자로 알려 주고, 엔티티의 boolean과 스키마 검사가 맞지 않았다(#168).
-- 이미 적용된 V20261022_1010은 고치지 않는다. MODIFY는 인덱스 *_ix_is_demo를 그대로 둔다

ALTER TABLE zone_stat_hourly
    MODIFY is_demo TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1이면 생성 스크립트로 넣은 시연용 데이터';

ALTER TABLE zone_stat_daily
    MODIFY is_demo TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1이면 생성 스크립트로 넣은 시연용 데이터';
