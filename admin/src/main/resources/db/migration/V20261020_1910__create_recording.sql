-- 녹화 기록(영상 1개 + 좌표 파일 1개): tables.md 'recording'. 분석 서버가 녹화 파일을 등록한다(FNC-A-026). 30일 지난 행은 매일 지운다

CREATE TABLE recording (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '녹화 번호',
    camera_id BIGINT NOT NULL COMMENT '녹화한 캠(camera.id)',
    started_at DATETIME(3) NOT NULL COMMENT '녹화 시작 시각(밀리초까지)',
    ended_at DATETIME(3) NULL COMMENT '녹화 종료 시각. 진행 중이면 NULL',
    video_path VARCHAR(255) NOT NULL COMMENT '영상 파일 경로(공유 폴더 기준 상대경로)',
    box_path VARCHAR(255) NOT NULL COMMENT '좌표 파일 경로(.json.gz, 공유 폴더 기준 상대경로)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '기록 등록 시각',
    CONSTRAINT recording_pk PRIMARY KEY (id),
    CONSTRAINT recording_fk_camera FOREIGN KEY (camera_id) REFERENCES camera (id)
) ENGINE = InnoDB;

CREATE INDEX recording_ix_camera_started ON recording (camera_id, started_at);
CREATE INDEX recording_ix_started ON recording (started_at);
