# 0021. 현재 인원 5초 갱신을 DB 표로 만들었다가 관리자 앱 메모리 직접 읽기로 바꿈

- 기록: 2026-10-29
- 관련: 이슈 #299, PR #362, white-eyes-docs PR #123·결정 0018, FNC-A-054, FNC-C-016·022
- 상태: 해결

## 증상
캠을 가동해도 대시보드의 '현재 인원' 숫자가 첫 1분 통계가 저장될 때까지(최대 약 70초) 바뀌지 않았고, 이후에도 1분에 한 번만 바뀌었다(#299). 명세(FNC-A-028, FNC-C-016·022)대로의 동작이라 5초 갱신을 새로 설계해야 했다.

## 원인
- 숫자가 1분 통계(`zone_stat_minute`)에서만 나왔다. 그 통계는 분 끝 + 5초(`MINUTE_CLOSE_GRACE_SEC`)에 저장되고, 통계 SSE가 5초마다 새 분을 확인하므로 빠르게 할 수 없었다.
- 분석 서버가 1초마다 보내는 인원은 관리자 앱의 **메모리 누적기**(`MinuteAccumulatorStore`)에만 있었다. 사용자 앱은 별개 프로세스라 그 메모리를 읽을 수 없다.

## 해결
**처음 만든 것(PR #362 첫 커밋)**: 두 앱이 같이 읽도록 DB 표 `zone_now_count`(구역당 1행)를 새로 만들고, 관리자 앱이 5초마다 메모리 누적기의 가장 최근 초를 그 표에 upsert했다(`NowCountService`, 스케줄러 `saveNowCounts`). 읽는 쪽은 `NowCountReader`가 표를 읽었다. 시험 환경에서 5초마다 바뀌는 것을 확인했다.

**리뷰에서 바뀐 방향(PM 결정, white-eyes-docs#123 리뷰)**:
- 5초 현재 인원은 **관리자 앱에만** 적용한다. 사용자 앱에는 캠을 가동하고 실시간 영상을 보는 화면이 없고(perm-003) 1분 통계 기준으로 충분하다.
- 그러면 DB 표가 필요 없다. 15초만 쓰고 버리는 값을 DB에 두면 마이그레이션, 사용자 앱 계정 권한(`db-grants`), 저장 타이머와 SSE 확인 타이머 두 개가 생기고, 저장 주기와 읽기 주기가 어긋나 지연이 최대 약 10초로 늘어난다.

**지금 구조**:
- 표·마이그레이션(`V20261028_1530__create_zone_now_count.sql`)·`ZoneNowCount`·`ZoneNowCountRepository`·`NowCountReader`·`NowCountService`·`saveNowCounts` 스케줄을 없앴다.
- common에 `NowCountSource` 인터페이스를 두고, 관리자 앱의 `MemoryNowCountSource`가 `MinuteAccumulatorStore.findLatestSecondByZone()`를 직접 읽는다(그 초가 `NOW_COUNT_FRESH_SEC` 15초보다 오래되면 쓰지 않음).
- common의 `DashboardSummaryService`·`MapStatusService`·`StatStreamBroadcaster`는 `ObjectProvider<NowCountSource>`로 받는다. 사용자 앱에는 빈이 없어 `currentCount`가 null이고 `event: now`도 보내지 않으며, 화면은 `countAvg`로 되돌아간다.
- 통계 SSE의 5초 확인이 메모리를 바로 읽어 `event: now`로 보낸다. DB 조회가 없다.

## 재발 방지
- 새 표를 만들기 전에 "그 값이 얼마나 오래 쓰이는가, 어느 앱이 읽는가"를 먼저 묻는다. 한쪽 앱에서만 쓰는 짧은 수명의 값은 표 대신 메모리로 충분한지 본다. 결정 기록: white-eyes-docs `decisions/0018-current-count-5sec.md`(고려한 대안에 DB 표 공유, 나중에 사용자 앱도 필요하면 관리자 앱 내부 API `/internal/**`에서 당겨 오는 방식을 적어 둠).
- common 모듈(두 앱이 같이 씀)에 한쪽 앱에서만 있는 기능을 넣을 때는 인터페이스와 `ObjectProvider`로 선택적 의존을 둔다(위 `NowCountSource`).
- **이 변경 전에 PR #362의 첫 커밋 마이그레이션을 이미 적용한 DB**(시험 환경, 각자 local)는 파일이 사라져 Flyway 검사 오류가 난다(`Detected applied migration not resolved locally: 20261028.1530`). 그 DB를 다시 만들거나(white-eyes-deploy에서 `docker compose down -v` 후 `up -d mysql`) `flyway repair` 후 `DROP TABLE zone_now_count`로 정리한다. 이 표를 쓰는 코드는 없으므로 남겨 두어도 동작에는 영향이 없다.
