# 0002. dev를 합친 뒤 admin:test가 AdminAccount 클래스를 못 찾아 실패(재현 안 됨)

- 기록: 2026-10-20
- 관련: 이슈 #5, PR #108, FNC-A-001
- 상태: 원인 미확인

## 증상
feature/5-super-admin-init에 origin/dev를 merge한 직후 `./gradlew build`에서 `:admin:test`가 실패했다.
한글 Windows 10, JDK 17.0.20.1, Gradle 9.8.1 환경이다.

```
JUnit Jupiter > initializationError FAILED
    org.junit.platform.launcher.core.DiscoveryIssueException
1 test completed, 1 failed
```

테스트 결과 XML에는 테스트 클래스 2개(`SuperAdminRestartTest`, `SuperAdminInitializerTest`)의 탐색 실패가 있었다.

```
Cause: java.lang.NoClassDefFoundError: com/whiteeyes/admin/account/entity/AdminAccount
Caused by: java.lang.ClassNotFoundException: com.whiteeyes.admin.account.entity.AdminAccount
```

- `admin/build/classes/java/main/.../AdminAccount.class`는 실제로 있었다.
- 테스트 classpath를 출력해 보니 `admin/build/classes/java/main`이 들어 있었다.
- 같은 오류가 두 번 났다. 두 번째는 `./gradlew --stop` 후 `./gradlew clean build`에서도 났다.
  (그 전의 `clean`은 `common/build/libs/common-0.0.1-SNAPSHOT.jar`를 지우지 못해 `:common:clean`에서 실패했다.
  Gradle 데몬을 멈추자 지워졌다.)
- 그 뒤 `./gradlew build`는 아무것도 바꾸지 않았는데 성공했다(테스트 69개 통과).

## 원인
미확인. 소스 코드, 클래스 파일, classpath에는 문제가 보이지 않았다.
이 변경 이후 검토·검증 에이전트가 전체 빌드와 `:admin:test --rerun-tasks`를 합쳐 30회 넘게 돌렸지만 다시 나지 않았다.

## 해결
없음. 다시 실행하자 통과했다. 원인을 모르므로 해결이 아니다.

## 재발 방지
같은 오류를 만나면 다시 돌리기 전에 먼저 남긴다.
1. `admin/build/test-results/test/*.xml`을 다른 곳에 복사한다(다시 돌리면 덮어써진다).
2. `./gradlew --status`로 Gradle 데몬 수와 상태를 본다.
3. 그다음 `./gradlew --stop` 후 `./gradlew build --rerun-tasks`로 재현되는지 본다.

재현되는 경우 이 문서에 '갱신' 항목으로 원인을 덧붙인다.
