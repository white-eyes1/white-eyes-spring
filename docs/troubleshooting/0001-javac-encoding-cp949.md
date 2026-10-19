# 0001. 한글 Windows에서 javac가 소스를 CP949로 읽어 한글이 깨짐

- 기록: 2026-10-19
- 관련: 이슈 #1, PR #92, FNC-C-027
- 상태: 우회

## 증상
한글 Windows에서 `./gradlew build` 시 한글 주석이 있는 Java 파일에서 컴파일 실패.

```
error: unmappable character (0xEC) for encoding x-windows-949
```

CI(ubuntu)에서는 발생하지 않는다.
우회 명령으로 빌드하더라도 UTF-8 설정이 없는 Gradle 데몬이 같이 떠 있으면, 그 데몬이 컴파일할 때
한글 문자열이 오류 없이 깨진 글자로 들어가 테스트에서 `입력값을 확인해 주세요.`가 `�엯�젰媛믪쓣...`로 나왔다.

## 원인
build.gradle에 컴파일 인코딩 설정이 없어 javac가 OS 기본 인코딩(CP949)을 쓴다.
Java 17은 기본 인코딩이 OS를 따른다(UTF-8 기본은 Java 18부터).
Gradle JVM을 UTF-8로 지정하고 데몬을 정리한 뒤 다시 빌드하면 정상인 것으로 확인했다.

## 해결
우회:

```
./gradlew --stop
./gradlew "-Dorg.gradle.jvmargs=-Dfile.encoding=UTF-8" build
```

근본 해결은 build.gradle `subprojects`에 아래를 추가한다(별도 PR, leads 리뷰).

```groovy
tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}
```

## 재발 방지
빌드 설정에 소스 인코딩을 명시한다(white-eyes-docs code-conventions.md '2-2. 형식'의 UTF-8 규칙).
근본 해결 전까지는 한글이 깨진 테스트 실패를 보면 `./gradlew --status`로 데몬이 여러 개인지 먼저 확인한다.
