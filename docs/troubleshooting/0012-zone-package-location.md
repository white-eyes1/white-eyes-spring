# 0012. Zone 클래스를 package-structure.md와 다른 `zone` 패키지에 만듦

- 기록: 2026-10-20
- 관련: 이슈 #10·#11, PR #107·#109
- 상태: 해결

## 증상
리뷰에서 Zone 엔티티·리포지토리는 `common/region/`, 구역 기능은 `admin/region/`에 두라고 요청받았다. 구현은 `common/zone/`, `admin/zone/`에 있었다.

## 원인
package-structure.md를 확인하지 않고 도메인 이름대로 `zone` 패키지를 새로 만들었다.

## 해결
`git mv`로 파일 8개를 region 아래로 옮기고 package·import만 바꿨다(로직 변경 없음). #11~#14 브랜치에도 병합해 맞췄다.

## 재발 방지
새 클래스를 만들기 전에 package-structure.md를 먼저 본다. 스택 PR은 아래 브랜치에서 옮기고 위로 병합하면 git이 파일 이동을 따라간다(충돌 시 0013 참고).
