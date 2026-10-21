-- 사용자 계정: tables.md 'user_account'. 탈퇴해도 행을 지우지 않아 같은 아이디로 재가입할 수 없다(FNC-U-001)

CREATE TABLE user_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '사용자 번호',
    login_id VARCHAR(20) NOT NULL COMMENT '로그인 아이디',
    password VARCHAR(60) NOT NULL COMMENT 'bcrypt로 암호화한 비밀번호',
    name VARCHAR(50) NOT NULL COMMENT '이름',
    email VARCHAR(100) NULL COMMENT '메일 주소',
    phone VARCHAR(20) NULL COMMENT '연락처',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '계정 상태: PENDING(미승인) / ACTIVE(정상) / WITHDRAWN(탈퇴)',
    must_change_password TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1이면 다음 로그인 때 비밀번호를 바꿔야 함',
    last_login_at DATETIME NULL COMMENT '마지막 로그인 성공 시각',
    withdrawn_at DATETIME NULL COMMENT '탈퇴 시각',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 수정 시각',
    CONSTRAINT user_account_pk PRIMARY KEY (id),
    CONSTRAINT user_account_uk_login_id UNIQUE (login_id)
) ENGINE = InnoDB;

CREATE INDEX user_account_ix_status_created_at ON user_account (status, created_at);
