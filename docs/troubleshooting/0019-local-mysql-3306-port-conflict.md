# 0019. 관리자 앱이 local MySQL(3306)에 붙지 못함(Windows MySQL80·WSL 포트 전달)

- 기록: 2026-10-23
- 관련: 이슈 #169, #33 작업 중 발견
- 상태: 해결

## 증상
local 프로필로 관리자 앱을 띄우자(`./gradlew :admin:bootRun --args='--spring.profiles.active=local'`) DB 연결에서 멈췄다. 순서대로 두 가지가 나왔다.

1. 처음:
   ```
   Access denied for user 'whiteeyes'@'localhost' (using password: YES)
   ```
2. Windows의 `MySQL80` 서비스를 멈춘 뒤:
   ```
   Communications link failure
   Connection refused
   ```

`docker ps`에는 `white-eyes-mysql`이 `0.0.0.0:3306->3306/tcp`로 떠 있었다.

## 원인
- 1번: 호스트의 3306을 잡고 있던 것은 컨테이너가 아니라 Windows에 설치된 `MySQL80` 서비스였다. 앱이 그쪽에 붙어 `.env`의 계정·비밀번호가 맞지 않았다.
- 2번: `MySQL80`을 멈춘 뒤에도 컨테이너의 3306이 호스트로 전달되지 않아(WSL 포트 전달) 호스트 3306에 아무것도 듣고 있지 않았다. `docker ps`의 포트 표시만으로는 실제로 듣고 있는지 알 수 없다.

## 해결
- `MySQL80`을 멈춘 채 컨테이너를 재시작했다(`docker compose restart`, 다시 만들 필요 없음). `Test-NetConnection localhost -Port 3306`이 `True`가 되는 것을 확인하고 앱을 띄웠다.
- Windows `MySQL80`을 멈출 수 없으면(다른 작업에 쓰는 경우) 컨테이너를 다른 포트로 띄운다. 저장소의 `docker-compose.yml`은 고치지 않고, 커밋하지 않는 compose 덮어쓰기 파일에서 `ports: !override ["3307:3306"]`로 바꾸고 `.env`의 `DB_URL`도 같은 포트로 맞춘다. 이 방법으로 MySQL 8.4 컨테이너(3307)에 관리자 앱이 붙는 것을 확인했다(#168 확인 때).

## 재발 방지
DB 연결 오류가 나면 계정·비밀번호를 고치기 전에 그 포트를 누가 듣는지부터 본다(PowerShell).

```powershell
Get-NetTCPConnection -LocalPort 3306 -State Listen   # 3306을 듣는 프로세스(OwningProcess)
Get-Service MySQL*                                    # Windows MySQL 서비스 상태
Test-NetConnection localhost -Port 3306               # 실제로 붙는지
```

## 이후 바뀐 점
2026-10-26부터 local MySQL은 이 저장소의 `docker-compose.yml`이 아니라 white-eyes-deploy에서 띄운다. 포트를 바꿀 때는 덮어쓰기 파일 대신 그쪽 `.env`의 `MYSQL_PORT`를 바꾸고 `DB_URL`도 같은 포트로 맞춘다.
