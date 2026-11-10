package com.whiteeyes.common.history.entity;

/** 행동한 계정의 종류. 사용자·관리자 계정 테이블이 나뉘어 있어 actor_id만으로는 구분할 수 없다. */
public enum ActorType {
    USER,
    ADMIN
}
