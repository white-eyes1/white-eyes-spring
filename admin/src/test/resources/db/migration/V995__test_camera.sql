-- TODO(#11): 캠 등록(FNC-A-018)에서 정식 camera 마이그레이션이 생기면 이 파일을 지운다.
-- 테스트에서만 실행되는 마이그레이션이다(src/test/resources). 임시 Camera 엔티티에 있는 칼럼만 둔다(이름·타입은 tables.md 'camera').
-- Hibernate가 스키마를 만들게 하면(create-drop·update) Flyway가 만든 다른 테이블까지 바꿔서 이 방식으로 만든다.
-- #17·#18 브랜치에서 같은 내용으로 유지한다

CREATE TABLE camera (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '캠 번호',
    zone_id BIGINT NOT NULL COMMENT '담당 구역(zone.id)',
    source_type VARCHAR(10) NOT NULL COMMENT '입력 방식: LIVE / DUMMY',
    run_requested TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1이면 가동을 원하는 상태',
    status VARCHAR(20) NOT NULL DEFAULT 'STOPPED' COMMENT '실제 상태: STOPPED / PREPARING / RUNNING / ERROR',
    progress TINYINT NOT NULL DEFAULT 0 COMMENT '분석 준비 진행률(0~100)',
    error_message VARCHAR(500) NULL COMMENT '마지막 오류 내용',
    deleted_at DATETIME NULL COMMENT '삭제 시각(NULL이면 사용 중)',
    CONSTRAINT camera_pk PRIMARY KEY (id)
) ENGINE = InnoDB;
