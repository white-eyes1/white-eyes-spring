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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 사용자 계정(tables.md user_account). 탈퇴해도 행을 지우지 않고 status만 WITHDRAWN으로 바꾼다.
 */
// JPA 엔티티. user_account 테이블 한 행에 대응한다
@Entity
@Table(name = "user_account")
@Getter
// JPA가 쓰는 기본 생성자만 열어 두고(protected), 만들 때는 createPending 같은 정적 메서드를 쓰게 한다
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    // 기본키. DB의 자동 증가 값(IDENTITY)을 쓴다
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인 아이디. 중복 불가이며 탈퇴해도 행이 남아 같은 아이디로 다시 가입할 수 없다
    @Column(name = "login_id", nullable = false, length = 20, unique = true)
    private String loginId;

    // bcrypt 해시 문자열(60자). 평문은 저장하지 않는다
    @Column(nullable = false, length = 60)
    private String password;

    // 이름
    @Column(nullable = false, length = 50)
    private String name;

    // 메일. 선택 값이라 null 허용
    @Column(length = 100)
    private String email;

    // 연락처. 선택 값이라 null 허용
    @Column(length = 20)
    private String phone;

    // 계정 상태. 숫자 순서가 아니라 이름 문자열로 저장해 enum 순서를 바꿔도 데이터가 깨지지 않는다
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserAccountStatus status;

    // 1이면 다음에 비밀번호를 바꿔야만 서비스를 쓸 수 있다(관리자 재설정 직후)
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    // 마지막 로그인 성공 시각
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // 탈퇴 처리한 시각. 탈퇴하지 않았으면 null
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    // 아래 두 시각 칸은 insertable·updatable=false로 JPA가 값을 쓰지 않고, @Generated로 INSERT·UPDATE 뒤에 DB가 채운 값을 읽어 온다
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
    // 새 계정 객체를 만들고 칸을 채운다. 가입 직후라 승인 대기 상태이고 비밀번호 변경 강제는 꺼 둔다
    public static UserAccount createPending(
            String loginId, String encodedPassword, String name, String email, String phone) {
        UserAccount account = new UserAccount();
        account.loginId = loginId;
        account.password = encodedPassword;
        account.name = name;
        account.email = email;
        account.phone = phone;
        // 관리자가 승인(approve)하기 전까지는 로그인할 수 없는 상태다
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
        // 해시로 교체하고, 아래에서 변경 강제를 끈다(스스로 바꿨으므로 더는 강제할 이유가 없다)
        this.password = encodedPassword;
        this.mustChangePassword = false;
    }

    /** 이름·메일·연락처를 고친다(본인 FNC-C-006, 관리자 FNC-A-004). 아이디·상태는 이 메서드로 바꿀 수 없다. */
    public void updateProfile(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    /**
     * 관리자가 임시 비밀번호로 재설정한다. 다음 로그인 때 비밀번호를 바꾸게 변경 강제를 켠다(FNC-A-005).
     *
     * @param encodedPassword bcrypt로 암호화한 임시 비밀번호
     */
    public void resetPassword(String encodedPassword) {
        // 관리자가 정한 임시 비밀번호로 교체하고, 아래에서 변경 강제를 켜 사용자가 곧바로 자기 비밀번호로 바꾸게 한다
        this.password = encodedPassword;
        this.mustChangePassword = true;
    }

    /** 로그인에 성공한 시각을 남긴다(FNC-C-001). */
    public void recordLogin(LocalDateTime loggedInAt) {
        this.lastLoginAt = loggedInAt;
    }

    /** 승인해 ACTIVE로 바꾼다(FNC-A-006). PENDING인지는 부르는 쪽이 먼저 확인한다. */
    public void approve() {
        this.status = UserAccountStatus.ACTIVE;
    }

    /** 승인을 취소해 PENDING으로 되돌린다(FNC-A-007). ACTIVE인지는 부르는 쪽이 먼저 확인한다. */
    public void unapprove() {
        this.status = UserAccountStatus.PENDING;
    }

    /** 탈퇴 처리한다(FNC-U-004). 행은 지우지 않으므로 같은 아이디로 다시 가입할 수 없다. */
    public void withdraw(LocalDateTime withdrawnAt) {
        this.status = UserAccountStatus.WITHDRAWN;
        this.withdrawnAt = withdrawnAt;
    }
}
