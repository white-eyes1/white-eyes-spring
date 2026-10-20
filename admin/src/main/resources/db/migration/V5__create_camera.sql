-- 캠(구역 1개에 1대): tables.md 'camera'. 상태(status·progress·error_message)는 분석 서버가 보고한 값으로 바꾼다(FNC-A-018)

CREATE TABLE camera (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '캠 번호',
    zone_id BIGINT NOT NULL COMMENT '담당 구역(zone.id). 구역당 삭제되지 않은 캠 1대(앱에서 확인)',
    name VARCHAR(50) NOT NULL COMMENT '캠 이름',
    source_type VARCHAR(10) NOT NULL COMMENT '입력 방식: LIVE / DUMMY',
    source_uri VARCHAR(255) NOT NULL COMMENT '영상 주소: LIVE는 RTSP 주소, DUMMY는 공유 폴더 기준 상대경로',
    run_requested TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1이면 가동을 원하는 상태(가동하면 1, 정지하면 0). 실제 상태(status)와 따로 둠',
    status VARCHAR(20) NOT NULL DEFAULT 'STOPPED' COMMENT '실제 상태: STOPPED / PREPARING / RUNNING / ERROR',
    progress TINYINT NOT NULL DEFAULT 0 COMMENT '분석 준비 진행률(0~100)',
    error_message VARCHAR(500) NULL COMMENT '마지막 오류 내용',
    first_started_at DATETIME NULL COMMENT '처음 가동된 시각(NULL이면 가동 이력 없음)',
    deleted_at DATETIME NULL COMMENT '삭제 시각(NULL이면 사용 중)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 수정 시각',
    CONSTRAINT camera_pk PRIMARY KEY (id),
    CONSTRAINT camera_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
) ENGINE = InnoDB;

CREATE INDEX camera_ix_zone_deleted ON camera (zone_id, deleted_at);
CREATE INDEX camera_ix_run_requested ON camera (run_requested);
