# 앱·분석 서버로 전달하는 모든 위치의 공통 설정(nginx.conf가 위치마다 include 한다).
# 앱이 원래 요청이 HTTPS였는지·진짜 클라이언트 주소가 무엇인지 알 수 있게 한다(나중에 HTTPS로 바꾸기 쉽게).
proxy_http_version 1.1;
proxy_set_header Connection "";
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
