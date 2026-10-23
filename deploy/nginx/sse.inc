# SSE 응답 공통 설정(nginx.conf의 SSE location 세 곳이 proxy.inc 와 함께 include 한다).
# 응답을 모으지 않고 바로 내보내고, 앱이 30초마다 주석 줄을 보내 연결을 유지하므로 읽기 제한을 1시간으로 둔다.
proxy_buffering off;
proxy_cache off;
proxy_read_timeout 1h;
