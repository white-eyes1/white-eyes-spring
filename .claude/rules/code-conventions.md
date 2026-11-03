---
paths:
  - "**/*.java"
  - "**/*.js"
  - "**/*.html"
---
# 코드 컨벤션
- 코드를 쓰거나 고치기 전에 `../white-eyes-docs/code-conventions.md`(Java는 2·3·6·7장, JavaScript는 5장)와 `../white-eyes-docs/package-structure.md`를 Read로 읽고 따른다. 규칙 원본은 white-eyes-docs이고, 여기에 옮겨 적지 않는다.
- 두 문서를 읽지 못하면 작업을 멈추고 사람에게 알린다. 읽지 않은 채 진행하지 않는다.
- Lombok 허용 목록은 `lombok.config`가, 빈 필드 `private final`·엔티티 equals/toString은 `ArchitectureTest`가 빌드에서 확인한다. 빌드가 실패하면 규칙을 우회하지 말고 코드를 고친다.
