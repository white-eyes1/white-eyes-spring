# spring — 드론 군중 관제(사용자 앱·관리자 앱)

명세 목록: @../white-eyes-docs/INDEX.md
코드 컨벤션·패키지 구조: white-eyes-docs의 code-conventions.md, package-structure.md

## 일하는 순서
- 작업은 GitHub 이슈 하나 단위. 시작 전에 `gh issue view <번호>`로 이슈를 읽는다.
- 이슈의 FNC·API·SCR ID에 해당하는 white-eyes-docs 섹션을 읽고 나서 코드를 쓴다.
- 명세와 다르게 만들어야 하면 멈추고 사람에게 묻는다. 명세를 임의로 해석하지 않는다.
- 검수 기준은 테스트로 만든다.
- 이슈 본문의 `## 넘겨받은 일`도 그 이슈에서 할 일이다(다른 이슈에서 미뤄 넘어온 일).
- 선행 작업이 dev에 없어 처리 순서 일부를 미루면, 그 일을 맡을 열린 이슈 본문 끝 `## 넘겨받은 일`에 체크 항목(할 일, 출처 이슈·PR, 코드 위치)을 추가한다. 받을 이슈가 없으면 멈추고 사람에게 후속 이슈를 요청한다. PR 본문에는 넘긴 이슈 번호를 적는다.
- 미룬 일을 코드 주석이나 다른 이슈의 코멘트로만 남기지 않는다. 작업을 시작하는 사람은 자기 이슈 본문만 읽기 때문이다.

## 구조
- common: 공통 도메인·통계 화면(두 앱이 같이 씀)
- user: 사용자 앱(포트 8080)
- admin: 관리자 앱(웹 8081, 내부 8082). 예약 작업·Flyway는 admin에만

## 명령
- 빌드·테스트: `./gradlew build`
- local DB: `cp .env.example .env` 후 값을 채우고 `docker compose up -d` (MySQL 8.4)
- 환경변수 불러오기: `set -a; source .env; set +a`
- 사용자 앱: `./gradlew :user:bootRun --args='--spring.profiles.active=local'`
- 관리자 앱: `./gradlew :admin:bootRun --args='--spring.profiles.active=local'`

## 규칙
- 키·비밀번호는 환경변수로만. 새 변수는 .env.example에 이름만 추가한다.
- 더미 영상·시연 데이터·가중치 파일은 커밋하지 않는다.
- 시연용 데이터는 Flyway가 아니라 --job=generate-demo로만 넣는다. prod 프로필에서는 시연용 작업이 실행되지 않게 막는다.
- 설정은 프로필(local·dev·prod)과 환경변수로 나눈다.
- 로깅은 Log4j2다. Logback(spring-boot-starter-logging)을 다시 들이지 않는다.
- 테이블·칼럼은 tables.md와 같아야 한다. 다르면 tables.md PR을 먼저 올린다.

## 트러블슈팅
- 원인을 찾는 데 오래 걸렸거나 다른 팀원도 겪을 문제를 해결하면 `docs/troubleshooting/`에 기록한다. 기준·양식은 그 폴더의 README.md.
- 원인을 확인하지 못했으면 '미확인(추정: …)'으로 쓴다. 비밀값은 쓰지 않는다.

## git
- 브랜치: feature/<이슈 번호>-<이름>. PR 대상은 항상 dev.
- main·dev에 직접 push하지 않는다.
