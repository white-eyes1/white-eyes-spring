# spring — 드론 군중 관제(사용자 앱·관리자 앱)

명세 목록: @../white-eyes-docs/INDEX.md
코드 컨벤션·패키지 구조: white-eyes-docs의 code-conventions.md, package-structure.md

## 일하는 순서
- 작업은 GitHub 이슈 하나 단위. 시작 전에 `gh issue view <번호>`로 이슈를 읽는다.
- 이슈의 FNC·API·SCR ID에 해당하는 white-eyes-docs 섹션을 읽고 나서 코드를 쓴다.
- 명세와 다르게 만들어야 하면 멈추고 사람에게 묻는다. 명세를 임의로 해석하지 않는다.
- 검수 기준은 테스트로 만든다.

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

## git
- 브랜치: feature/<이슈 번호>-<이름>. PR 대상은 항상 dev.
- main·dev에 직접 push하지 않는다.
