-- TODO(#10): 구역 등록(FNC-A-014, PR #107)의 정식 zone 마이그레이션(V4)이 dev에 들어오면 이 파일을 지운다.
-- 테스트에서만 실행되는 마이그레이션이다(src/test/resources). 임시 Zone 엔티티에 있는 칼럼만 둔다(이름·타입은 tables.md 'zone').

CREATE TABLE zone (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '구역 번호',
    area_m2 DECIMAL(10, 2) NOT NULL COMMENT '면적(㎡)',
    CONSTRAINT zone_pk PRIMARY KEY (id)
) ENGINE = InnoDB;
