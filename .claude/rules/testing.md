---
paths:
  - "admin/src/test/**"
---
# admin 테스트의 로그인 방식
관리자 앱은 모든 요청이 로그인 대상이다. 시험 목적에 따라 로그인 방식을 고른다.

- 로그인만 되어 있으면 되는 웹(MockMvc) 기능 시험: `@Import(AdminTestUserConfig.class)`.
  모든 요청에 `admin01`(ADMIN) 사용자와 CSRF 토큰을 붙인다.
- 작업 기록(FNC-C-026)이 남는 시험(구역·캠 등록·수정·삭제, 가동·정지, 계정 상태 변경 등): 요청마다 `.with(AdminTestLogin.admin())`.
  작업 기록은 행동한 계정을 `AccountPrincipal`에서 채우는데, `AdminTestUserConfig`의 사용자는 이름만 있어 쓸 수 없다. 이 경우 `@Import`는 붙이지 않아도 된다.
- 권한 시험(비로그인 401, 관리자 403, 최고 관리자 전용 등): 위 둘을 쓰지 않고 실제 로그인(`POST /login`)한 세션으로 요청한다.
  `AdminTestUserConfig`를 붙이면 모든 요청에 ADMIN 사용자가 붙어 비로그인·역할 차이를 만들 수 없다. 비로그인 401은 `AdminTestUserConfig`를 쓰지 않는 클래스에서 확인한다.
