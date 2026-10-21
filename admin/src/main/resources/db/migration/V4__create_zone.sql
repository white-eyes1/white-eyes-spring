-- 구역(캠 1대가 촬영하는 범위): tables.md 'zone'. 관리자가 지도에 다각형을 그려 등록한다(FNC-A-014)

CREATE TABLE zone (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '구역 번호',
    dong_id BIGINT NOT NULL COMMENT '소속 동(region.id). 다각형 중심점이 속한 읍·면·동으로 자동 판정',
    name VARCHAR(50) NOT NULL COMMENT '구역 이름. 같은 동의 삭제되지 않은 구역끼리 중복 불가(앱에서 확인)',
    area_m2 DECIMAL(10, 2) NOT NULL COMMENT '면적(㎡). 다각형에서 자동 계산',
    boundary JSON NOT NULL COMMENT '구역 다각형(GeoJSON, WGS84)',
    center_lat DECIMAL(9, 6) NOT NULL COMMENT '구역 중심점 위도(다각형 안의 점)',
    center_lng DECIMAL(9, 6) NOT NULL COMMENT '구역 중심점 경도',
    deleted_at DATETIME NULL COMMENT '삭제 시각(NULL이면 사용 중)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 수정 시각',
    CONSTRAINT zone_pk PRIMARY KEY (id),
    CONSTRAINT zone_fk_dong FOREIGN KEY (dong_id) REFERENCES region (id)
) ENGINE = InnoDB;

CREATE INDEX zone_ix_dong_deleted ON zone (dong_id, deleted_at);
