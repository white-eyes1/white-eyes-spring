# 0018. nginx auth_request 주소에 변수를 썼더니 권한 확인이 항상 통과함

- 기록: 2026-10-23
- 관련: 이슈 #159, FNC-A-041
- 상태: 해결

## 증상
`/admin/live/{cameraId}`에 `auth_request /_live_auth/$camera_id;`를 쓰고 가짜 앱으로 확인하니, 쿠키가 없는 요청과 권한이 없는 캠(403을 줘야 하는 캠)의 요청이 모두 200으로 분석 서버까지 전달됐다. 가짜 앱 기록에는 권한 확인 요청이 `/_live_auth/$camera_id`라는 글자 그대로 사용자 앱으로 간 것이 남았다.

## 원인
`auth_request`의 주소는 변수를 풀어 주지 않는 글자 그대로의 주소다. 그래서 하위 요청이 `/_live_auth/$camera_id`로 가서 일치하는 위치가 없었고, 맨 아래 `location /`(사용자 앱)이 200을 돌려줘 인증이 통과로 처리됐다. 권한 확인이 조용히 통과로 바뀌어 보안 문제로 이어질 수 있는 실수였다.

## 해결
`auth_request`는 고정 주소(`/_live_auth`)로 쓰고, 카메라 번호는 하위 요청에서도 처음 요청의 주소를 가리키는 `$request_uri`에서 `map`으로 뽑아 관리자 앱 권한 확인 주소(`?cameraId=$live_camera_id`)에 넘긴다.
이어서 같은 시험에서 비튼 주소(`/admin/live/1/../2/snapshot`, `/admin/live/%31/snapshot`)는 확인한 번호(원래 주소에서 뽑음)와 분석 서버로 보낼 번호(정리된 주소에서 뽑음)가 어긋날 수 있음을 확인해, 두 값이 다르면 `if (...) { return 403; }`으로 막았다.

## 재발 방지
- nginx 설정은 `nginx -t`(문법)만으로 끝내지 말고, 권한이 있을 때와 없을 때를 모두 요청해 보는 시험을 한다(`deploy/nginx/verify/run.sh`). 특히 권한 확인은 '막혀야 할 요청이 막히는지'를 본다.
- 위치에 `proxy_set_header`가 하나라도 있으면 서버 수준 `proxy_set_header`를 물려받지 못한다(같은 시험에서 `Host`가 사라진 것으로 발견). 공통 헤더는 `proxy.inc`로 모아 위치마다 include 한다.
