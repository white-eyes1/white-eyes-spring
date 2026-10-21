-- 작업 기록: tables.md 'work_history'. 쌓기만 하고 수정·삭제하지 않는다(FNC-C-026)

CREATE TABLE work_history (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '작업 기록 번호',
    actor_type VARCHAR(10) NOT NULL COMMENT '행동한 계정 종류: USER / ADMIN',
    actor_id BIGINT NOT NULL COMMENT '행동한 계정 번호(user_account.id 또는 admin_account.id)',
    actor_login_id VARCHAR(20) NOT NULL COMMENT '기록 당시 행동한 계정의 아이디',
    action VARCHAR(30) NOT NULL COMMENT '행동 종류: EXCEL_DOWNLOAD / EVENT_STATUS / ACCOUNT_CHANGE / CAMERA_RUN / ZONE_CHANGE / CAMERA_CHANGE',
    target_type VARCHAR(20) NULL COMMENT '대상 종류: USER_ACCOUNT / ADMIN_ACCOUNT / ZONE / CAMERA / EVENT / REGION / ALL. 통계 다운로드는 범위에 따라 ZONE·REGION·ALL, 이벤트 다운로드는 EVENT(target_id 없음)',
    target_id BIGINT NULL COMMENT '대상 번호(전체 다운로드면 NULL)',
    target_name VARCHAR(100) NULL COMMENT '기록 당시 대상 이름(아이디·구역명 등)',
    detail JSON NULL COMMENT '상세: 다운로드 조건(종류·지역·기간·항목), 이전 값·바뀐 값(이벤트는 처리 상태·조치 항목·메모), 가동/정지 등',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '기록 시각',
    CONSTRAINT work_history_pk PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX work_history_ix_created_at ON work_history (created_at);
CREATE INDEX work_history_ix_actor ON work_history (actor_type, actor_login_id, created_at);
CREATE INDEX work_history_ix_action ON work_history (action, created_at);
CREATE INDEX work_history_ix_target ON work_history (target_type, target_id, created_at);
