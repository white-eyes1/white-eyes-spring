-- 사용자 목록(FNC-A-002) 시험 데이터. 테스트의 현재 시각은 2026-10-21 14:00(KST), 활동 중 기준 30분
-- 1 hong01: 세션 없음, 10분 전 로그인 → 활동 안 함(세션이 없으면 활동 중이 아님, #203)
-- 2 kim02: 로그인한 적 없음
-- 3 lee03: 가입일이 10-12 하루의 끝, 5분 전 세션 접근 → 활동 중
-- 4 park04: 탈퇴, 가입일이 10-13 0시
-- 5 hong05: 40분 전 세션 접근(로그인보다 늦음) → 활동 안 함
-- 6 pct06: 이름에 % 가 들어 있음
INSERT INTO user_account (id, login_id, password, name, email, phone, status, last_login_at, created_at)
VALUES (1, 'hong01', 'x', '홍길동', 'hong@example.com', '010-1111-1111', 'ACTIVE', '2026-10-21 13:50:00', '2026-10-01 09:00:00');
INSERT INTO user_account (id, login_id, password, name, status, last_login_at, created_at)
VALUES (2, 'kim02', 'x', '김철수', 'PENDING', NULL, '2026-10-05 10:00:00');
INSERT INTO user_account (id, login_id, password, name, status, last_login_at, created_at)
VALUES (3, 'lee03', 'x', '이영희', 'ACTIVE', '2026-10-20 10:00:00', '2026-10-12 23:59:59');
INSERT INTO user_account (id, login_id, password, name, status, last_login_at, withdrawn_at, created_at)
VALUES (4, 'park04', 'x', '박민수', 'WITHDRAWN', '2026-10-10 08:00:00', '2026-10-11 09:00:00', '2026-10-13 00:00:00');
INSERT INTO user_account (id, login_id, password, name, status, last_login_at, created_at)
VALUES (5, 'hong05', 'x', '홍길순', 'ACTIVE', '2026-10-21 12:00:00', '2026-10-15 12:00:00');
INSERT INTO user_account (id, login_id, password, name, status, last_login_at, created_at)
VALUES (6, 'pct06', 'x', '100%회원', 'PENDING', NULL, '2026-10-16 12:00:00');

-- 세션 마지막 접근 시각(epoch ms): lee03 2026-10-21 13:55, hong05 2026-10-21 13:20
INSERT INTO SPRING_SESSION_USER (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
VALUES ('20000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', 1792558500000, 1792558500000, 1800, 1792560300000, 'lee03');
INSERT INTO SPRING_SESSION_USER (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
VALUES ('20000000-0000-0000-0000-000000000005', '30000000-0000-0000-0000-000000000005', 1792556400000, 1792556400000, 1800, 1792558200000, 'hong05');
