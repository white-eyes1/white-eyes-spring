-- 관리자 계정: tables.md 'admin_account'. 최고 관리자는 앱이 처음 켜질 때 자동으로 등록된다(FNC-A-001)

CREATE TABLE admin_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '관리자 번호',
    login_id VARCHAR(20) NOT NULL COMMENT '로그인 아이디',
    password VARCHAR(60) NOT NULL COMMENT 'bcrypt로 암호화한 비밀번호',
    name VARCHAR(50) NOT NULL COMMENT '이름',
    email VARCHAR(100) NULL COMMENT '메일 주소',
    phone VARCHAR(20) NULL COMMENT '연락처',
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN' COMMENT '권한 등급: ADMIN / SUPER_ADMIN',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '계정 상태: ACTIVE(정상) / DISABLED(비활성화)',
    must_change_password TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1이면 다음 로그인 때 비밀번호를 바꿔야 함',
    last_login_at DATETIME NULL COMMENT '마지막 로그인 성공 시각',
    created_by BIGINT NULL COMMENT '이 계정을 만든 최고 관리자(admin_account.id). 자동 등록된 최고 관리자는 NULL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 수정 시각',
    CONSTRAINT admin_account_pk PRIMARY KEY (id),
    CONSTRAINT admin_account_uk_login_id UNIQUE (login_id),
    CONSTRAINT admin_account_fk_created_by FOREIGN KEY (created_by) REFERENCES admin_account (id)
) ENGINE = InnoDB;

CREATE INDEX admin_account_ix_role_status_created_at ON admin_account (role, status, created_at);
