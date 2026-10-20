# 0017. 캠 목록의 통계·이벤트 데이터가 dev에 없어 응답 일부를 채우지 못함

- 기록: 2026-10-20
- 관련: 이슈 #12, PR #110, FNC-A-022
- 상태: 우회

## 증상
`GET /api/v1/manage/cameras`가 돌려줘야 하는 `stat`(최근 1분 통계·혼잡 단계), `todayEventCount`, `recent10m`을 채울 수 없었다.

## 원인
데이터 출처가 dev에 없다.
- `zone_stat_minute`: 팀원 #18(PR #102)
- 혼잡 단계 계산: #19(PR #104)
- `event` 테이블·엔티티: 어디에도 없음

## 해결
있는 것(캠·상태·진행률·구역·지역 경로·면적)만 먼저 구현했다. 응답 모양은 명세와 같게 두고, 해당 칸은 `stat=null`, `todayEventCount=0`, `recent10m=[]`로 내렸다.
데이터가 들어오면 `CameraListService.toItem`과 테스트 `list_statAndEventsNotYetConnected`를 함께 고친다. "새로고침 없이 갱신(SSE)"은 이슈상 2단계라 이번 범위에서 뺐다.

## 재발 방지
다른 이슈의 데이터에 의존하는 이슈는 시작 전에 데이터 출처가 dev에 있는지 확인하고 범위를 팀과 정한다.
