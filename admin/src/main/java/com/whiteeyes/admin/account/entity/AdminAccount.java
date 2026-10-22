package com.whiteeyes.admin.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 계정(tables.md admin_account). 관리자 앱만 쓰는 테이블이라 common이 아니라 admin에 둔다(package-structure.md 4장).
 */
@Entity
@Table(name = "admin_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, length = 20, unique = true)
    private String loginId;

    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminStatus status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // 연관관계 대신 id 값으로 둔다. 자동 등록된 최고 관리자는 만든 사람이 없어 NULL
    @Column(name = "created_by")
    private Long createdBy;

    // 두 시각은 DB 기본값이 채운다
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * 앱이 처음 켜질 때 자동 등록하는 최고 관리자(FNC-A-001). 첫 로그인 때 비밀번호를 바꾸게 한다.
     *
     * @param encodedPassword 이미 bcrypt로 바꾼 비밀번호. 평문을 넘기지 않는다
     */
    public static AdminAccount createSuperAdmin(String loginId, String encodedPassword, String name) {
        AdminAccount account = new AdminAccount();
        account.loginId = loginId;
        account.password = encodedPassword;
        account.name = name;
        account.role = AdminRole.SUPER_ADMIN;
        account.status = AdminStatus.ACTIVE;
        account.mustChangePassword = true;
        return account;
    }

    /** 로그인에 성공한 시각을 남긴다(FNC-C-001). */
    public void recordLogin(LocalDateTime loggedInAt) {
        this.lastLoginAt = loggedInAt;
    }

    /**
     * 비밀번호를 바꾸고 변경 강제를 끈다(FNC-C-007).
     *
     * @param encodedPassword bcrypt로 암호화한 비밀번호
     */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.mustChangePassword = false;
    }
}
