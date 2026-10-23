# nginx 설정 (웹 서버)

외부에서 접근할 수 있는 유일한 입구(포트 80)의 설정이다. 규칙의 원본은 white-eyes-docs `conventions.md` '프록시·nginx 경로 규칙'이고, 이 폴더는 그 규칙을 그대로 옮긴 것이다. 규칙을 바꾸려면 명세를 먼저 고친다.

| 파일 | 역할 |
|---|---|
| `nginx.conf` | 전체 설정. 서버 주소는 맨 위 `upstream` 세 곳(user 8080, admin 8081, 분석 서버 5000)만 환경에 맞게 바꾼다 |
| `proxy.inc` | 앱·분석 서버로 전달하는 모든 위치의 공통 헤더(`Host`, `X-Forwarded-*`). 위치에 `proxy_set_header`가 하나라도 있으면 바깥 설정의 헤더를 물려받지 못해서 파일로 모아 위치마다 include 한다 |
| `sse.inc` | SSE 위치 공통(`proxy_buffering off`, `proxy_read_timeout 1h`) |
| `internal-token.conf.example` | 분석 서버로 보낼 `X-Internal-Token` 값을 담을 파일의 견본. 복사해 `/etc/nginx/internal-token.conf`로 두고 `INTERNAL_API_TOKEN` 값을 채운다. **진짜 값이 든 파일은 저장소에 올리지 않는다** |
| `verify/` | 가짜 앱으로 이 설정을 확인하는 스크립트(아래) |

## 두는 곳
`nginx.conf`·`proxy.inc`·`sse.inc`·`internal-token.conf`는 같은 폴더(`/etc/nginx/`)에 둔다. 영상·좌표 파일이 있는 공유 스토리지는 `/srv/media`에 **읽기 전용**으로 마운트한다.

## 무엇을 하는가
| 경로 | 동작 |
|---|---|
| `/` · `/admin/` | 사용자 앱(8080) · 관리자 앱(8081)으로 전달 |
| `/admin/live/{cameraId}`, `/admin/live/{cameraId}/snapshot` | `auth_request`로 관리자 앱 `GET /admin/api/v1/live/auth?cameraId=`를 확인해 204일 때만 분석 서버 `/analyzer/v1/cameras/{cameraId}/live`·`/snapshot`으로 전달. `X-Internal-Token`을 붙이고(클라이언트가 보낸 값은 덮어씀), 스트림은 `proxy_buffering off`, 미리보기는 `Cache-Control: no-store` |
| 위 두 경로의 카메라 번호 | 권한을 확인한 번호와 분석 서버로 보낼 번호가 다르면(예: `/admin/live/1/../2/snapshot`) 403으로 막는다 |
| `/api/v1/stats/stream`, `/admin/api/v1/stats/stream`, `/admin/api/v1/notifications/stream` | SSE. 정확히 일치하는 위치, `proxy_buffering off`, `proxy_read_timeout 1h`, `Connection` 비움 |
| `/protected/media/` | `internal`, `alias /srv/media/`. 관리자 앱이 `X-Accel-Redirect`로 지정할 때만 전송, Range 지원. 브라우저가 직접 입력하면 404 |
| `/protected/media/**/*.json.gz` | `Content-Type: application/json`, `Content-Encoding: gzip` |
| `/internal/`, `/admin/internal/` | 404 |

## 확인하는 법
Docker가 있는 곳(Windows는 WSL)에서 `verify/run.sh <설정 폴더>`를 실행한다. nginx:alpine 컨테이너에 이 설정을 올리고, 가짜 user·admin 앱과 분석 서버(`verify/fakes.py`)를 띄워 위 표를 하나씩 확인한 뒤 컨테이너를 지운다. 예:

```sh
sh deploy/nginx/verify/run.sh deploy/nginx
```

확인하는 것: 문법(`nginx -t`), 경로 분배와 프록시 헤더, 내부 경로 404, 라이브 권한(쿠키 없음 401·권한 없음 403·통과 200, 분석 서버가 받은 토큰, 비튼 주소 차단), 스트림·SSE가 모이지 않고 바로 나오는지, `X-Accel-Redirect`·Range 206·직접 접근 404, 좌표 파일 헤더(`Content-Encoding` 1개). 가짜 앱의 응답 규칙은 `fakes.py` 위쪽을 본다(실제 앱 규칙은 FNC-A-041·044 명세).

실제 앱·분석 서버와 함께 영상이 나오는지는 2026-10-23에 확인했다(모두 WSL Docker·Java 안). MySQL 8.0, 이 저장소 `dev`의 관리자 앱 jar(`--job=load-regions`로 행정구역 적재), 분석 서버(flask `dev` a575d49, python:3.12-slim·CPU torch·VisDrone 가중치), 이 설정의 nginx를 모두 실제로 띄우고 합성 시험 영상(`dummy/videos/a.mp4`)을 썼다. 브라우저(Chrome)로 nginx(80)를 거쳐 관리자 로그인 → 캠 관리 화면에서 **가동** 클릭 → 준비 중 진행률이 0%에서 90%까지 오르고 **가동 중**으로 바뀜 → **실시간 보기** 창에 분석 서버가 그린 MJPEG이 나옴 → 대시보드 아래 **캠 카드 미리보기**에 JPEG 프레임이 나옴(상태 정상, 1분 통계가 저장되어 "0명"으로 표시)을 확인했다. 이어서 `curl`로 `/admin/live/1/snapshot`의 `Cache-Control`이 한 번만 나가는지, 분석 서버에 토큰 없이 직접 요청하면 401인지, 클라이언트가 보낸 `X-Internal-Token`을 nginx가 덮어쓰는지, 작업 없는 캠이 404인지도 확인했다. 확인하지 못한 것: 사람이 찍힌 영상과 라이브(RTSP) 입력(합성 영상이라 탐지 수는 0), 운영 환경의 nginx, 분석 성능(이 PC의 CPU가 오래되어 판단하지 않음). 참고로 이 환경에서 관리자 앱을 실제 MySQL로 띄우면 `ddl-auto: validate`가 `zone_stat_hourly·zone_stat_daily.is_demo`(TINYINT)와 boolean 필드의 타입 차이로 멈춘다. 이 확인에서는 `SPRING_JPA_HIBERNATE_DDL_AUTO=none`으로 우회했고, 별도로 알린다.
