-- 행정구역(시·구·동): tables.md 'region'. 경계 파일을 --job=load-regions로 적재한다(FNC-A-012)

CREATE TABLE region (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '행정구역 번호',
    parent_id BIGINT NULL COMMENT '상위 행정구역(region.id). 시는 NULL',
    level VARCHAR(10) NOT NULL COMMENT '단위: SI / GU / DONG',
    code VARCHAR(10) NOT NULL COMMENT '행정구역 코드(경계 데이터 원본 코드)',
    name VARCHAR(50) NOT NULL COMMENT '행정구역 이름',
    boundary JSON NOT NULL COMMENT '경계 다각형(GeoJSON, 위도·경도 WGS84)',
    center_lat DECIMAL(9, 6) NOT NULL COMMENT '경계 중심점 위도(경계 안의 점)',
    center_lng DECIMAL(9, 6) NOT NULL COMMENT '경계 중심점 경도',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '초기 데이터 적재 시각',
    CONSTRAINT region_pk PRIMARY KEY (id),
    CONSTRAINT region_uk_code UNIQUE (code),
    CONSTRAINT region_fk_parent FOREIGN KEY (parent_id) REFERENCES region (id)
) ENGINE = InnoDB;

CREATE INDEX region_ix_parent ON region (parent_id);
