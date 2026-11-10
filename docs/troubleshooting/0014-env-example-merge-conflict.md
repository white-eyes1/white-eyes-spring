# 0014. 최신 dev를 합치자 `.env.example`에서 충돌

- 기록: 2026-10-20
- 관련: 이슈 #13, PR #112
- 상태: 해결

## 증상
`git merge origin/dev`에서 `.env.example`이 CONFLICT가 났다.

## 원인
dev에 병합된 #19(혼잡 단계)가 파일 끝에 `THRESHOLD_WARN_DENSITY`·`THRESHOLD_DANGER_DENSITY` 주석을 추가했다. 이 브랜치도 같은 위치에 `ANALYZER_BASE_URL`·`INTERNAL_API_TOKEN`을 추가해, 같은 줄 범위를 서로 다르게 바꿨다.

## 해결
충돌 표시를 지우고 양쪽 블록을 모두 남긴 뒤 커밋했다(7e3f90f). 전체 빌드를 다시 확인했다.

## 재발 방지
파일 끝에 줄을 추가하는 작은 파일은 병합 전에 dev를 자주 합친다. 충돌이 나면 한쪽을 지우지 말고 양쪽 변경을 모두 확인한다.
