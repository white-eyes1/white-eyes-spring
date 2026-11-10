# 0017. 한글 Windows에서 `@Sql`로 넣은 한글 데이터가 깨짐

- 기록: 2026-10-22
- 관련: 이슈 #32, PR #158, FNC-A-002
- 상태: 해결

## 증상
`UserListTest`가 `@Sql("/sql/account/user-list.sql")`로 사용자 시험 데이터를 넣는데, 한글 Windows에서 이름처럼 한글이 들어간 값이 깨진 글자로 저장되어 테스트 2개가 실패했다. SQL 파일 자체는 UTF-8이고, 오류 없이 깨진 값이 들어간다.

```
JSON path "$.items[0].name" expected:<이영희> but was:<�씠�쁺�씗>
JSON path "$.items[*].loginId" Expected: iterable containing ["hong01", "hong05"] but: no item was "hong01"
```

두 번째는 한글 이름 검색이 깨진 값과 맞지 않아 생긴 실패다.

## 원인
`@Sql`은 인코딩을 정하지 않으면 SQL 파일을 플랫폼 기본 인코딩으로 읽는다. 한글 Windows의 Java 17은 기본 인코딩이 MS949라 UTF-8 파일의 한글이 잘못 읽힌다(Java 18부터는 기본이 UTF-8).
`@SqlConfig(encoding = "UTF-8")`을 빼고 다시 돌리면 같은 2개가 실패하고, 넣으면 통과하는 것으로 확인했다(2026-10-22). [0001](0001-javac-encoding-cp949.md)은 컴파일러의 같은 종류 문제이고, 그때 고친 `options.encoding`은 소스 컴파일에만 적용되어 테스트가 읽는 SQL 파일에는 효과가 없다.

## 해결
`@Sql`에 인코딩을 지정했다(PR #158).

```java
@Sql(scripts = "/sql/account/user-list.sql", config = @SqlConfig(encoding = "UTF-8"))
```

## 재발 방지
한글 값이 든 SQL 파일을 `@Sql`로 읽을 때는 `@SqlConfig(encoding = "UTF-8")`을 함께 쓴다. CI(ubuntu)는 기본이 UTF-8이라 통과하므로 로컬 Windows에서만 깨질 수 있다. 한글이 주석에만 있는 파일(예: `user-sessions.sql`)은 넣는 값이 깨지지 않아 지금은 문제가 드러나지 않는다.
