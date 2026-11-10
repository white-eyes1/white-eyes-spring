# 0016. 세션을 읽기만 하려 했는데 확인 요청마다 세션 마지막 접근 시각이 갱신됨

- 기록: 2026-10-22
- 관련: 이슈 #43, FNC-A-041(실시간 화면·미리보기 권한 확인)
- 상태: 해결

## 증상
nginx `auth_request`용 `GET /api/v1/live/auth`가 쿠키의 세션을 저장소에서 `findById`로 조회만 하는데, 요청이 올 때마다 `SPRING_SESSION_ADMIN`의 `LAST_ACCESS_TIME`·`EXPIRY_TIME`이 늘었다. 이 확인은 카드 미리보기마다 2초 간격으로 오므로 관리자가 아무것도 안 해도 세션 만료(30분)가 계속 미뤄지는 문제다.

## 원인
`findById` 자체는 세션을 고치지 않는다(저장소를 직접 호출해 시각이 그대로인 것을 확인). 세션을 읽은 것은 요청 처리 중의 다른 코드였다. 세션 저장소 필터(`SessionRepositoryFilter`)는 요청 중에 세션이 한 번이라도 읽히면 응답이 끝날 때 그 세션을 저장하면서 마지막 접근 시각을 갱신한다. 읽은 곳을 `findById`에 스파이를 걸어 호출 스택으로 하나씩 찾았다.
1. 스프링 시큐리티의 익명 사용자 필터(인증 상세를 만들며 `request.getSession(false)`)
2. 스프링 시큐리티의 세션 관리 필터(`request.getRequestedSessionId()`)
3. 스프링 MVC `DispatcherServlet`의 플래시 맵 관리자(모든 요청에서 `request.getSession(false)`)

## 해결
- `AdminSecurityConfig`에 이 경로만 맡는 필터 체인을 따로 두고 `securityContext`·`anonymous`·`sessionManagement`·`requestCache`를 끈다.
- 컨트롤러가 아니라 `LiveAuthFilter`(보안 체인 안)가 204/401/403을 직접 쓰고 끝낸다. 컨트롤러로 만들면 3번 때문에 세션이 읽힌다.
- 테스트(`LiveAuthTest`)가 5번 확인한 뒤에도 `LAST_ACCESS_TIME`·`EXPIRY_TIME`이 그대로이고 새 세션·`Set-Cookie`가 없음을 확인한다.

## 재발 방지
세션을 갱신하지 않고 읽어야 하는 경로(주기적인 상태 확인 등)는 MVC 컨트롤러로 만들지 않는다. 필터 체인을 따로 두고 위 세 곳이 세션을 읽지 못하게 하며, "세션 시각이 그대로"를 테스트로 확인한다. 세션 만료 판정은 `EXPIRY_TIME` 열이 아니라 저장된 `LAST_ACCESS_TIME`과 `MAX_INACTIVE_INTERVAL`로 계산되므로, 만료 시험은 `LAST_ACCESS_TIME`을 과거로 바꿔야 한다.
