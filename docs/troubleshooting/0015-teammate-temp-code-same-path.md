# 0015. 팀원 브랜치의 임시 코드와 정식 코드가 같은 파일 경로라 합칠 때 충돌

- 기록: 2026-10-20
- 관련: 이슈 #11·#13, PR #109·#112 / 팀원 #15~#18 브랜치
- 상태: 우회(팀원 브랜치 정리 필요)

## 증상
- #15~#18 브랜치(rlaeoaud7018)에 `Camera`, `CameraStatus`, `CameraSourceType`, `CameraRepository`(#18에는 `Zone`, `ZoneRepository`도) 임시 코드가 같은 경로에 있다. `TODO(#11)`로 표시돼 있다.
- #15 브랜치에는 `camera/client/AnalyzerClient.java`(stop만)와 `camera/exception/CameraNotFoundException.java` 임시 코드가 있다. `TODO(#13)`로 표시돼 있다.

## 원인
팀원이 #10·#11·#13이 끝나기 전에 필요한 부분만 임시로 만들어 두었다.

## 해결
정식 코드가 임시 코드와 호환되게 했다.
- Camera: 같은 클래스·필드 이름·접근자·enum 값, `findByIdAndDeletedAtIsNull`, `updateStatus`.
- AnalyzerClient: 같은 클래스 이름, 설정 이름(`analyzer.base-url`, `internal-api.token`), `stopCamera(Long)` 시그니처. `CameraNotFoundException`은 내용을 똑같이 복사하고 `application.yml`·`.env.example` 추가 줄도 맞췄다.
- 임시 브랜치에서 #15~#18을 merge해 보니 컴파일은 통과했고, 임시 클래스에서만 add/add 충돌이 났다(정식 버전 하나만 남기면 된다).

남은 일: 팀원 test 설정의 `ddl-auto: create-drop`(`TODO(#11)`)을 지우고 `validate`로 되돌리면, 통합 테스트 5개(#17 3개, #18 2개)가 NOT NULL 컬럼(camera의 name·source_uri, zone의 dong_id 등) 때문에 실패한다. 테스트 데이터를 보강해야 한다.

## 재발 방지
다른 사람이 임시로 둔 코드가 있는 이슈는 시작 전에 어떤 부분을 누가 정식화할지 합의한다.
