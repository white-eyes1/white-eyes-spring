---
paths:
  - "admin/src/main/resources/db/migration/**"
  - "admin/src/test/resources/db/migration/**"
---
# DB 마이그레이션
- 테이블·칼럼은 white-eyes-docs/tables.md와 같아야 한다. 다르면 tables.md PR을 먼저 올린다.

## 파일 이름
- 새 파일은 만든 시각으로 버전을 매긴다: `V<yyyyMMdd>_<HHmm>__<설명>.sql` (예: `V20261021_1430__create_work_history.sql`).
  - 순서 번호(`V6`, `V7`…)를 쓰지 않는다. PR끼리 같은 번호를 잡아 머지 직전에 번호를 바꾸는 일이 반복됐기 때문이다.
  - 이미 있는 `V1`~`V5`는 그대로 둔다. Flyway는 버전을 숫자로 비교하므로 시각 버전은 항상 그 뒤에 실행된다.
- 다른 테이블을 참조하는(FK) 파일은 참조되는 테이블 파일보다 늦은 시각이어야 한다.
- local·dev 프로필은 `spring.flyway.out-of-order: true`라서 먼저 만든 파일이 늦게 머지돼도 적용된다. prod는 켜지 않는다.

## prod 첫 배포 전까지(배포하면 이 절을 지우고 아래 '배포 후'만 남긴다)
- 이미 dev에 합쳐진 V파일도 고칠 수 있다. 바꿀 내용은 새 파일로 쌓지 말고 그 테이블을 만드는 V파일에 직접 반영한다.
- 합쳐진 V파일을 고친 PR은 본문에 고친 파일을 적는다. 머지 뒤 각자 로컬 DB를 다시 만든다: `docker compose down -v` 후 `docker compose up -d`(행정구역 등 적재 작업도 다시 실행).
- 공용 dev 서버 DB가 있으면 그 DB도 같이 다시 만든다. 그대로 두면 체크섬이 달라 admin 앱이 뜨지 않는다.

## 배포 후
- 이미 합쳐진 V파일은 절대 고치지 않는다. 바꿀 내용은 새 버전 파일로 추가한다.

## 브랜치·테스트
- 다른 기능 브랜치 위에서 시작했으면, 그 브랜치가 dev에 머지된 뒤 바로 dev로 rebase(또는 merge)해서 이미 dev에 있는 V파일을 PR에서 없앤다.
- 테스트에만 필요한 테이블을 `admin/src/test/resources/db/migration/`에 V파일로 넣지 않는다(`V999`처럼 고정 번호를 쓰면 PR끼리 다시 충돌한다). 테스트 데이터는 `@Sql`로 넣고, 테이블이 아직 dev에 없으면 그 테이블을 만드는 PR이 머지된 뒤 작업한다.
