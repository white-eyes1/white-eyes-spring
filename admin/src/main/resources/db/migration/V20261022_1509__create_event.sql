-- 이벤트(혼잡 단계 상승·급증·캠 이상): tables.md 'event'. 같은 구역·종류의 열린 이벤트는 하나만 둔다(FNC-A-033). 처리 이력은 work_history에 남긴다

CREATE TABLE event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '이벤트 번호',
    type VARCHAR(20) NOT NULL COMMENT '종류: CONGESTION(혼잡 단계 상승) / SURGE(급증) / CAM_FAULT(캠 이상)',
    zone_id BIGINT NOT NULL COMMENT '발생 구역(zone.id)',
    camera_id BIGINT NULL DEFAULT NULL COMMENT '대상 캠(camera.id). 캠 이상일 때만',
    started_at DATETIME NOT NULL COMMENT '발생 시각(판정한 1분 통계의 시각)',
    ended_at DATETIME NULL DEFAULT NULL COMMENT '종료 시각. 열려 있으면 NULL',
    peak_level TINYINT NULL DEFAULT NULL COMMENT '최고 혼잡 단계(2: 주의, 3: 위험). 혼잡 이벤트만',
    peak_value DECIMAL(10, 4) NULL DEFAULT NULL COMMENT '최고 값: 혼잡은 밀집도(명/㎡), 급증은 증가율(%)',
    peak_count INT NULL DEFAULT NULL COMMENT '이벤트 동안 최고 인원(1분 평균 인원의 최댓값). 캠 이상은 NULL',
    below_since DATETIME NULL DEFAULT NULL COMMENT '기준 아래로 내려간 시각(혼잡: 단계가 주의 미만, 급증: 증가율이 기준 미만이거나 검사 조건 미충족). 다시 넘으면 NULL',
    threshold_snapshot JSON NOT NULL COMMENT '발생 당시 기준값(혼잡: 단계 경계 밀도, 급증: 비교 시간·증가율·최소 밀집도, 캠 이상: 결과 없음 허용 시간). 설정 파일 값',
    status VARCHAR(20) NOT NULL DEFAULT 'UNCHECKED' COMMENT '처리 상태: UNCHECKED(미확인) / CHECKED(확인 중) / RESOLVED(조치완료)',
    action_code VARCHAR(20) NULL DEFAULT NULL COMMENT '조치 항목: ONSITE_OK(현장 확인·정상) / STAFF(인력 배치) / FALSE_ALARM(오탐) / CAM_CHECK(캠 점검 요청) / OTHER(기타). 조치완료일 때만',
    action_note VARCHAR(200) NULL DEFAULT NULL COMMENT '직접 입력한 조치 내용',
    handled_by BIGINT NULL DEFAULT NULL COMMENT '마지막으로 처리 상태를 바꾼 관리자(admin_account.id)',
    handled_at DATETIME NULL DEFAULT NULL COMMENT '마지막 처리 시각',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '기록 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 수정 시각',
    CONSTRAINT event_pk PRIMARY KEY (id),
    CONSTRAINT event_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id),
    CONSTRAINT event_fk_camera FOREIGN KEY (camera_id) REFERENCES camera (id),
    CONSTRAINT event_fk_handled_by FOREIGN KEY (handled_by) REFERENCES admin_account (id)
) ENGINE = InnoDB;

CREATE INDEX event_ix_zone_type_ended_at ON event (zone_id, type, ended_at);
CREATE INDEX event_ix_started_at ON event (started_at);
CREATE INDEX event_ix_status ON event (status);
