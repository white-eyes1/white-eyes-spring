# 0020. 관리자 앱이 MySQL에서 스키마 검사(is_demo 형식)로 시작하지 못함

- 기록: 2026-10-23
- 관련: 이슈 #168, #169, PR #146(V20261022_1010), #33 작업 중 발견
- 상태: 해결

## 증상
dev를 local 프로필 + MySQL 8.4로 띄우면 관리자 앱이 시작하다 멈췄다. `./gradlew build`는 통과했다.

```
Schema validation: wrong column type encountered in column [is_demo] in table [zone_stat_daily];
found [tinyint (Types#TINYINT)], but expecting [bit (Types#BOOLEAN)]
```

## 원인
- `V20261022_1010__create_zone_stat_hourly_daily.sql`이 `is_demo`를 `TINYINT`로 만들었다. tables.md와 다른 참/거짓 칼럼(`camera.run_requested`, `*.must_change_password`)은 `TINYINT(1)`이다.
- MySQL 드라이버(Connector/J)는 `TINYINT(1)`만 BOOLEAN(BIT)으로 알려 주고, 그냥 `TINYINT`는 숫자로 알려 준다. 엔티티(`ZoneStatRollup.demo`)는 `boolean`이라 Hibernate의 스키마 검사(`ddl-auto: validate`)가 형식이 다르다고 거부했다.
- 테스트는 H2(MySQL 모드)로 돌아 이 차이가 드러나지 않았다.

## 해결
- 이미 적용된 `V20261022_1010`은 고치지 않았다(Flyway checksum이 바뀌어 다른 사람 DB에서 검증 오류가 난다).
- 새 마이그레이션 `V20261023_2138__fix_zone_stat_is_demo_type.sql`에서 두 테이블의 `is_demo`를 `MODIFY ... TINYINT(1) NOT NULL DEFAULT 0`으로 바꿨다(#168). 엔티티는 그대로 둔다.
- 확인한 것
  - `V20261022_1010`이 이미 적용된 local MySQL 8.4에서 새 마이그레이션이 오류 없이 적용되고, 관리자 앱이 `ddl-auto: validate` 그대로 뜬다(`Started AdminApplication`).
  - 적용 뒤 두 테이블의 `is_demo` 형식은 `tinyint(1)`, 기본값 0이고, 인덱스 `*_ix_is_demo`는 그대로 남는다.
  - 적용할 때 MySQL이 `Integer display width is deprecated`(1681) 경고를 남기지만 실패가 아니다. 8.4에서도 드라이버는 `TINYINT(1)`을 BOOLEAN으로 알려 준다.
  - H2 테스트에서도 같은 `MODIFY`가 적용되어 `./gradlew build`가 통과한다.
- #168이 dev에 들어오기 전의 임시 방법: `--args`에 `--spring.jpa.hibernate.ddl-auto=none`을 붙여 검사만 건너뛴다(local에서만, 설정 파일은 바꾸지 않는다).

## 재발 방지
- 참/거짓 칼럼은 tables.md대로 `TINYINT(1)`로 만든다. 마이그레이션의 형식 길이를 tables.md 표와 맞춰 본다.
- H2 테스트는 MySQL 드라이버의 형식 해석 차이를 잡지 못한다. 마이그레이션을 추가했으면 local MySQL로 관리자 앱을 한 번 띄워 스키마 검사를 확인한다. H2 쪽 차이는 [0010](0010-h2-tinyint-boolean-compare.md)도 참고.
