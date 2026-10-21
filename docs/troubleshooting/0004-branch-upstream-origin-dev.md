# 0004. origin/dev에서 만든 브랜치의 upstream이 origin/dev로 잡힘

- 기록: 2026-10-19
- 관련: 이슈 #7
- 상태: 해결

## 증상
`git checkout -b feature/... origin/dev`로 만든 브랜치가 `origin/dev`를 추적했다.
그대로 `git push`하면 feature 브랜치가 아니라 dev에 직접 올라간다(브랜치 규칙 위반).

## 원인
원격 브랜치를 시작점으로 주면 git이 그 브랜치를 upstream으로 자동 설정한다.

## 해결
`git push -u origin feature/7-load-regions:feature/7-load-regions`처럼 이름을 명시해 push하고, upstream을 feature 브랜치로 바꿨다.

## 재발 방지
브랜치를 만들 때 `git checkout --no-track -b feature/... origin/dev`를 쓴다.
