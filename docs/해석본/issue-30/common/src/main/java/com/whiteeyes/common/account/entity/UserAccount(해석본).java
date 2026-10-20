/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/account/entity/UserAccount.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002), 테이블: tables.md user_account
 *
 * [무엇] user_account 테이블의 JPA 엔티티. #29에서 만든 파일에 #30이 createPending 하나를 더했다.
 * [#29 해석본과의 관계] 필드 매핑 설명은 docs/해석본/issue-29/의 같은 파일에 있다. 여기서는 바뀐 부분만 설명한다.
 * [하지 않은 것] 승인·탈퇴 같은 상태 변경 메서드는 여전히 없다. 승인은 관리자 앱 이슈, 탈퇴는 #64(FNC-U-004)에서 추가한다.
 */
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

    // [해석] 왜 정적 팩토리인가: 기본 생성자는 JPA용으로 protected로 막아 두었다(#29). public 생성자를 열면 아무 상태로나
    //        계정을 만들 수 있으므로, '가입으로 만드는 계정'이라는 뜻이 이름에 드러나는 메서드 하나만 연다.
    //        status를 부르는 쪽이 고르지 못하게 PENDING으로 고정한다(FNC-U-002 처리 순서 3, tables.md '가입 시 PENDING').
    //        비밀번호는 이미 암호화된 값을 받는다. 엔티티가 PasswordEncoder를 모르게 하려는 것으로, 암호화는 서비스가 한다.
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
        // [해석] tables.md DEFAULT 0과 같은 값. boolean 필드라 안 써도 false지만, 가입 계정은 비밀번호 변경 강제가 아니라는 뜻을
        //        드러내려고 적었다. 1이 되는 것은 관리자가 임시 비밀번호로 재설정했을 때뿐이다(acman-001).
        account.mustChangePassword = false;
        // [해석] id·created_at·updated_at은 넣지 않는다. id는 INSERT 때 DB가 만들고(IDENTITY), 두 시각은 DB 기본값이 채운다(@Generated).
        return account;
    }
}
