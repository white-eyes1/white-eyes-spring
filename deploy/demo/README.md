# 시연용 통계 생성 설정(FNC-A-051)

`generate-demo.example.json`은 `--job=generate-demo`에 넘기는 설정 견본이다. 복사해서 날짜·구역 값을 바꿔 쓴다.
만든 시연용 데이터는 커밋하지 않는다(설정 파일만 둔다). local·dev 프로필에서만 실행된다.

```bash
set -a; source .env; set +a
./gradlew :admin:bootRun --args='--spring.profiles.active=local --job=generate-demo --config=deploy/demo/generate-demo.example.json'
```

- 실행하면 기존 시연용 데이터(is_demo=1)를 먼저 지우고 다시 만든다. 실제 데이터(is_demo=0)는 그대로 둔다.
- 구역마다 `startDate`부터 그 구역 캠이 처음 가동된 날의 전날(가동한 적이 없으면 어제)까지 시간별·일별 통계를 만든다. 1분 통계는 만들지 않는다.
- 삭제되지 않은 모든 구역에 만든다. 구역 이웃(FNC-A-016)을 먼저 등록해 두면 구역 간 이동량이 이웃끼리 맞춰진다.
- 지우기만 하려면 `--job=delete-demo`(FNC-A-052).

| 항목 | 뜻 |
|---|---|
| `seed` | 무작위 변동의 시작값. 같은 seed·같은 구역이면 같은 결과 |
| `startDate` | 만들기 시작하는 날짜(KST, `yyyy-MM-dd`) |
| `defaultZone` | `zones`에 없는 구역에 쓸 기본 인원·평균 체류. 비우면 모든 구역을 `zones`에 적어야 한다 |
| `zones` | 구역별 값: `{"zoneId": 1, "baseCount": 50, "avgDwellSec": 900}` |
| `baseCount` | 기본 인원(가중치가 모두 1일 때 한 시간 평균 인원) |
| `avgDwellSec` | 평균 체류(초). 시간당 나가는 인원 = 평균 인원 × 3600 ÷ 평균 체류 |
| `hourWeights` | 0~23시 가중치 24개 |
| `dayOfWeekWeights` | 요일 가중치 7개(MONDAY~SUNDAY) |
| `seasonWeights` | 계절 가중치 4개: 3~5월 SPRING, 6~8월 SUMMER, 9~11월 AUTUMN, 12~2월 WINTER |
| `surgeDays` | 특정일 급증: `{"date": "2026-05-05", "weight": 2.5}`. 적지 않은 날은 1 |

시간마다 인원 = 기본 인원 × 시간대 × 요일 × 계절 × 특정일 가중치 × 무작위(±10%).
설정에 모르는 항목이 있거나 값이 빠지면 아무것도 지우지 않고 멈춘다.
