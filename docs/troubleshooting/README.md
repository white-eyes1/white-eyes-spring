# 트러블슈팅 기록

개발하면서 겪은 문제와 해결 과정을 남기는 곳이다. 같은 문제를 다른 팀원과 Claude가 다시 겪지 않게 하는 것이 목적이다.

## 결정 기록과의 차이
- white-eyes-docs의 `decisions/`는 설계·방침을 **고른 것**과 이유를 남긴다.
- 여기는 구현 중에 **생긴 문제**와 원인·해결을 남긴다.
- 해결하면서 앞으로의 방침이 생기면 white-eyes-docs의 결정 기록 또는 code-conventions.md를 PR로 고치고, 여기서는 링크만 건다.

## 기록할 때
다음 중 하나면 기록한다.
- 원인을 찾는 데 10분 이상 걸렸다.
- 다른 팀원이나 Claude도 같은 문제를 만날 수 있다.

기록하지 않는다: 오타, 단순 컴파일 오류, 바로 보이는 설정 실수.

## 쓰는 방식
- 파일 하나에 사건 하나. 파일명은 `NNNN-짧은-영문.md`(같은 폴더에서 가장 큰 번호 + 1). 양식은 `_template.md`.
- 문제를 고친 PR에 같이 담는다. 기록만 따로 올려도 된다.
- 원인을 확인하지 못했으면 '원인: 미확인(추정: …)'으로 쓴다. 근거 없는 추측을 사실처럼 쓰지 않는다.
- 비밀번호·키·토큰 값은 쓰지 않는다. 환경변수 이름만 쓴다. 로그를 붙일 때 값이 섞이지 않았는지 확인한다.
- 한 번 쓴 기록은 고치지 않는다. 나중에 원인이 밝혀지거나 해결이 바뀌면 '갱신' 항목을 덧붙인다.

## 목록
(기록이 생기면 한 줄씩 추가: `- [0001 제목](0001-xxx.md)`)
- [0001 한글 Windows에서 javac가 소스를 CP949로 읽어 한글이 깨짐](0001-javac-encoding-cp949.md)
- [0002 dev를 합친 뒤 admin:test가 AdminAccount 클래스를 못 찾아 실패(재현 안 됨)](0002-admin-test-noclassdef-adminaccount.md)
- [0003 경계 파일의 지역 코드 자릿수 가정이 실제 파일과 달랐다(7자리 → 8자리)](0003-region-file-code-length.md)
- [0004 origin/dev에서 만든 브랜치의 upstream이 origin/dev로 잡힘](0004-branch-upstream-origin-dev.md)
- [0005 문자열 치환으로 만든 오류 입력이 치환되지 않아 테스트가 실패](0005-test-replace-did-not-apply.md)
- [0006 JTS `Polygon.copy()`가 `Geometry`를 돌려줘 형변환 컴파일 오류](0006-jts-copy-returns-geometry.md)
- [0007 `GeoJsonReader` 패키지를 찾을 수 없음(jts-core에 없음)](0007-jts-geojsonreader-module.md)
- [0008 로컬은 통과하는데 CI에서 400 응답 테스트 2개가 실패](0008-ci-400-test-fails-after-dev-merge.md)
- [0009 테스트가 남긴 행 때문에 다른 테스트 19개가 외래키 오류로 실패](0009-tests-leave-rows-fk-failure.md)
- [0010 H2가 TINYINT(1)을 숫자로 돌려줘 boolean 비교 테스트가 실패](0010-h2-tinyint-boolean-compare.md)
- [0011 여러 PR이 같은 마이그레이션 번호를 가져가 겹침(V3·V4·V5)](0011-migration-version-collision.md)
- [0012 Zone 클래스를 package-structure.md와 다른 `zone` 패키지에 만듦](0012-zone-package-location.md)
- [0013 스택 브랜치를 반복문으로 병합하다 충돌 표시가 커밋됨](0013-stack-merge-conflict-markers-committed.md)
- [0014 최신 dev를 합치자 `.env.example`에서 충돌](0014-env-example-merge-conflict.md)
- [0015 팀원 브랜치의 임시 코드와 정식 코드가 같은 파일 경로라 합칠 때 충돌](0015-teammate-temp-code-same-path.md)
