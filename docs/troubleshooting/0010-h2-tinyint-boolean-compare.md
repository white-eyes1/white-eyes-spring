# 0010. H2가 TINYINT(1)을 숫자로 돌려줘 boolean 비교 테스트가 실패

- 기록: 2026-10-20
- 관련: 이슈 #11, PR #109
- 상태: 해결

## 증상
`CameraRegisterTest`의 첫 테스트가 실패했다.

```
expected: false but was: 0
```

## 원인
`JdbcTemplate`으로 직접 읽은 `run_requested`(TINYINT(1))를 H2가 숫자 0으로 돌려주는데, 테스트가 boolean `false`와 비교했다. 구현 버그가 아니라 테스트 버그다(API 응답의 `runRequested:false`는 정상).

## 해결
`((Number) row.get("RUN_REQUESTED")).intValue()`로 숫자 0을 비교한다.

## 재발 방지
DB 드라이버가 돌려주는 자바 타입은 DB마다 다르다(H2 TINYINT는 숫자, MySQL TINYINT(1)은 boolean). 직접 읽는 테스트는 타입에 의존하지 않게 쓴다.
