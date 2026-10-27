# 시연용 통계 생성 설정(FNC-A-051)

`--job=generate-demo`로 시연용 시간별·일별 통계를 만든다. **설정 파일 없이 실행해도 된다**(기본값이 코드에 있다).
`generate-demo.example.json`은 기본값을 그대로 적은 견본이다. 급증일이나 구역별 인원을 바꿀 때만 복사해서 바꾸려는 항목만 남겨 `--config`로 넘긴다.
만든 시연용 데이터는 커밋하지 않는다. local·dev 프로필에서만 실행된다.

```bash
set -a; source .env; set +a
# 모든 구역, 기본 설정
DB_USERNAME=white_eyes_admin DB_PASSWORD=$ADMIN_DB_PASSWORD ./gradlew :admin:bootRun --args='--spring.profiles.active=local --job=generate-demo'
# 1·2번 구역만, 설정 파일로 일부 값 바꿈
DB_USERNAME=white_eyes_admin DB_PASSWORD=$ADMIN_DB_PASSWORD ./gradlew :admin:bootRun --args='--spring.profiles.active=local --job=generate-demo --zone=1,2 --config=demo/generate-demo.example.json'
```
`bootRun`은 admin 모듈 폴더에서 돌아서 `--config`는 그 폴더 기준 경로다(`demo/…` = `admin/demo/…`). DB 계정은 CLAUDE.md '명령'과 같이 관리자 앱 계정을 쓴다.
전체를 컨테이너로 띄웠다면 white-eyes-deploy에서 `docker compose run --rm generate-demo`(옵션을 붙이면 `command`가 통째로 바뀌므로 `--job=generate-demo`도 함께 쓴다).

- 구역마다 **끝 시각 직전까지** 만든다. 끝 시각은 그 구역 캠이 처음 가동된 시각이 속한 정시이고, 가동한 적이 없으면 실행 시각이 속한 정시다. 시작은 끝 시각의 1년 전 날짜 0시다. 1분 통계는 만들지 않는다.
  - 첫 가동 뒤에 실행하면 실제 통계와 시간별·일별이 끊기지 않는다. 가동 전에 실행했다면 가동한 뒤 한 번 더 실행한다.
  - 일별은 24시간이 모두 시연용인 날만 만든다. 끝 시각이 든 날이 오늘이면 자정 집계가 시연용과 실제를 함께 묶고, 지난 날이면 생성 작업이 일별 집계로 묶는다(그날 일별이 이미 있으면 그대로 둔다).
- `--zone`이 없으면 삭제되지 않은 모든 구역을 만들고, 기존 시연용 데이터(is_demo=1)를 모두 지운 뒤 다시 만든다. `--zone`이 있으면 그 구역의 시연용 행만 지우고 다시 만든다. 없거나 삭제된 구역을 주면 아무것도 지우지 않고 멈춘다. 실제 데이터(is_demo=0)는 그대로 둔다.
- 무작위 값은 (seed, 구역, 시각)으로 정해져 언제 실행하든 같은 시각의 값이 같다. 그래서 일부 구역만 다시 만들어도 이웃(FNC-A-016)과 이동량 짝이 맞는다. 이웃의 설정이나 이웃 관계를 바꿨다면 관련 구역을 함께 다시 만든다.
- 지우기만 하려면 `--job=delete-demo`(FNC-A-052).

| 항목 | 뜻 |
|---|---|
| `seed` | 무작위 변동의 시작값. 같은 seed·같은 구역·같은 시각이면 같은 값 |
| `defaultZone` | `zones`에 없는 구역에 쓸 기본 인원·평균 체류 |
| `zones` | 구역별 값: `{"zoneId": 1, "baseCount": 50, "avgDwellSec": 900}` |
| `baseCount` | 기본 인원(가중치가 모두 1일 때 한 시간 평균 인원) |
| `avgDwellSec` | 평균 체류(초). 시간당 나가는 인원 = 평균 인원 × 3600 ÷ 평균 체류 |
| `hourWeights` | 0~23시 가중치 24개 |
| `dayOfWeekWeights` | 요일 가중치 7개(MONDAY~SUNDAY) |
| `seasonWeights` | 계절 가중치 4개: 3~5월 SPRING, 6~8월 SUMMER, 9~11월 AUTUMN, 12~2월 WINTER |
| `surgeDays` | 특정일 급증: `{"date": "2026-05-05", "weight": 2.5}`. 적지 않은 날은 1. 기본값에는 없다 |

시간마다 인원 = 기본 인원 × 시간대 × 요일 × 계절 × 특정일 가중치 × 무작위(±10%).
설정 파일에 적지 않은 항목은 기본값을 쓴다. 모르는 항목이 있거나 값이 틀리면 아무것도 지우지 않고 멈춘다. 예전 파일의 `startDate`는 무시한다(경고 로그).
