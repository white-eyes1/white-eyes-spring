/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/account/entity/UserAccountStatus.java (29-fnc-u-001-아이디-중복-확인 브랜치)
 * 관련 이슈: #29(FNC-U-001), 테이블: tables.md user_account.status
 *
 * [무엇] 사용자 계정 상태 세 가지. tables.md의 'PENDING(미승인) / ACTIVE(정상) / WITHDRAWN(탈퇴)'를 철자 그대로 옮겼다
 *   (code-conventions 2-1 '코드 값은 conventions.md·tables.md와 같은 철자').
 * [왜 enum인가] DB에는 VARCHAR로 저장하지만(tables.md 공통 규칙: ENUM 타입을 쓰지 않음) 코드에서는 오타를 막으려고 enum으로 다룬다.
 *   엔티티 쪽에서 @Enumerated(EnumType.STRING)으로 이름 그대로 저장한다.
 * [이번 이슈에서 쓰는 곳] 중복 확인은 상태를 보지 않으므로 서비스 코드에서는 쓰지 않는다. 테스트에서 탈퇴·미승인·정상 행을 만들 때 쓴다.
 */
package com.whiteeyes.common.account.entity;

/** 사용자 계정 상태(tables.md user_account.status). */
public enum UserAccountStatus {
    // [해석] 가입하면 PENDING(FNC-U-002), 관리자가 승인하면 ACTIVE, 탈퇴하면 WITHDRAWN(FNC-U-004).
    //        탈퇴해도 행은 남아서 그 아이디로 다시 가입할 수 없다(auth-005). 그래서 중복 확인에서 이 값도 '사용 중'이다.
    PENDING,
    ACTIVE,
    WITHDRAWN
}
