package com.whiteeyes.common.account.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 계정(tables.md user_account). 탈퇴해도 행을 지우지 않고 status만 WITHDRAWN으로 바꾼다.
 */
@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

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
    private UserAccountStatus status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    // DB 기본값(CURRENT_TIMESTAMP)이 채운다.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // DB 기본값과 ON UPDATE가 채운다.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * 회원가입으로 만드는 계정. 관리자가 승인하기 전까지 PENDING이다(FNC-U-002).
     *
     * @param encodedPassword bcrypt로 암호화한 비밀번호
     */
    public static UserAccount createPending(
            String loginId, String encodedPassword, String name, String email, String phone) {
        UserAccount account = new UserAccount();
        account.loginId = loginId;
        account.password = encodedPassword;
        account.name = name;
        account.email = email;
        account.phone = phone;
        account.status = UserAccountStatus.PENDING;
        account.mustChangePassword = false;
        return account;
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

    /** 로그인에 성공한 시각을 남긴다(FNC-C-001). */
    public void recordLogin(LocalDateTime loggedInAt) {
        this.lastLoginAt = loggedInAt;
    }

    /** 승인해 ACTIVE로 바꾼다(FNC-A-006). PENDING인지는 부르는 쪽이 먼저 확인한다. */
    public void approve() {
        this.status = UserAccountStatus.ACTIVE;
    }
}
