# 0008. 로컬은 통과하는데 CI에서 400 응답 테스트 2개가 실패

- 기록: 2026-10-20
- 관련: 이슈 #9, PR #105, FNC-C-027
- 상태: 해결

## 증상
PR #105의 CI `build`가 `ZonePreviewTest`의 400 응답 테스트 2개(INVALID_POLYGON, OUT_OF_SERVICE_AREA)에서 실패했다. 로컬에서는 통과했다.

## 원인
CI는 PR을 최신 dev와 합친 상태로 빌드한다. 그 사이 dev에 공통 오류 처리(`GlobalExceptionHandler`, FNC-C-027)가 병합되어, 구역 전용 임시 핸들러와 응답 모양이 달라졌다.

## 해결
dev를 합쳐 로컬에서 재현한 뒤 고쳤다(커밋 79cd0fc).
- `ErrorCode`에 INVALID_POLYGON·OUT_OF_SERVICE_AREA를 추가했다.
- 두 예외를 `BusinessException` 상속으로 바꾸고 임시 핸들러를 지웠다.

## 재발 방지
PR을 올리기 전에 최신 `origin/dev`를 merge해서 빌드·테스트를 다시 돌린다.
