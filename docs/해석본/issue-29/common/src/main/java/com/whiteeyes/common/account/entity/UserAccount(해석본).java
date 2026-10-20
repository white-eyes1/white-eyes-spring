/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/account/entity/UserAccount.java (29-fnc-u-001-아이디-중복-확인 브랜치)
 * 관련 이슈: #29(FNC-U-001), 테이블: tables.md user_account
 *
 * [무엇] user_account 테이블의 JPA 엔티티.
 * [왜 common인가] 사용자 앱은 가입·로그인에, 관리자 앱은 목록·승인·수정에 같은 테이블을 쓴다.
 *   두 앱이 함께 쓰는 테이블의 엔티티는 common에 둔다(package-structure 4장 표의 첫 줄에 user_account가 있음).
 * [왜 칼럼을 전부 매핑했나] 이번 기능은 login_id만 읽지만, 앱은 ddl-auto=validate로 엔티티와 테이블을 맞춰 본다.
 *   지금 테스트는 create-drop이라 이 엔티티대로 테이블이 만들어지므로, 칼럼 이름·길이·NULL 여부를 tables.md와 같게 두었다.
 *   나중에 마이그레이션이 생겨 validate로 돌아가도 그대로 통과하게 하려는 것이다.
 * [하지 않은 것] 생성자·상태 변경 메서드(가입, 승인, 탈퇴)는 넣지 않았다. 가입은 #30(FNC-U-002), 탈퇴는 #64(FNC-U-004)에서 필요한 만큼 추가한다.
 * [아직 없는 것] user_account 마이그레이션. 그래서 실제 DB에서는 테이블이 생길 때까지 두 앱 모두 시작하지 못한다(PR 본문에 명시).
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
// [해석] @Getter만 열고 setter는 열지 않는다(code-conventions 3-3 '엔티티에 public setter를 열지 않는다').
// [해석] @NoArgsConstructor(PROTECTED): JPA가 객체를 만들 때 쓰는 기본 생성자. 다른 코드가 빈 계정을 만들지 못하게 protected.
@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    // [해석] id: BIGINT AUTO_INCREMENT → IDENTITY 전략(MySQL이 번호를 매김).
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // [해석] login_id: VARCHAR(20), NOT NULL, UK. unique = true는 create-drop으로 만들 때 유일 조건을 같이 만들게 한다.
    //        이 유일 조건 덕분에 '같은 아이디가 두 행'인 경우가 없어서 중복 확인은 존재 여부만 보면 된다.
    @Column(name = "login_id", nullable = false, length = 20, unique = true)
    private String loginId;

    // [해석] password: bcrypt 결과는 항상 60자(tables.md 비고). 암호화는 common의 PasswordEncoderConfig(FNC-C-005)가 한다.
    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    // [해석] email·phone: 선택 입력이라 NULL 허용(nullable 기본값 true). 유일 조건 없음 — 중복 확인은 아이디만 한다.
    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    // [해석] status: VARCHAR(20)에 'PENDING' 같은 이름을 그대로 저장(EnumType.STRING).
    //        ORDINAL(0,1,2)로 저장하면 enum 순서를 바꿀 때 데이터 뜻이 바뀐다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserAccountStatus status;

    // [해석] must_change_password: TINYINT(1) 0/1을 Java boolean으로 받는다(MySQL 드라이버가 TINYINT(1)을 boolean으로 다룸).
    //        실제 MySQL에서 validate가 통과하는지는 마이그레이션이 생긴 뒤 확인해야 한다.
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    // [해석] 시각 칼럼은 DB가 채운다. insertable/updatable=false로 JPA가 값을 보내지 않게 하고,
    //        @Generated로 저장 뒤 DB가 넣은 값을 다시 읽어 온다(#100의 Region.createdAt과 같은 방식).
    // DB 기본값(CURRENT_TIMESTAMP)이 채운다.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // [해석] updated_at은 ON UPDATE CURRENT_TIMESTAMP라 수정할 때도 DB가 바꾼다. 그래서 INSERT·UPDATE 둘 다 다시 읽는다.
    // DB 기본값과 ON UPDATE가 채운다.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
