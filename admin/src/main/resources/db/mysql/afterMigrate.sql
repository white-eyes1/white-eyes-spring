-- 사용자 앱 계정에 표마다 데이터 권한(DML)을 준다(conventions.md 'DB 계정'). Flyway가 마이그레이션 뒤에 매번 돌린다.
-- 사용자 앱 계정은 admin_account·SPRING_SESSION_ADMIN 권한이 없고 DDL 권한도 없다.
-- MySQL은 "이 표만 빼고"를 줄 수 없어서 나머지 표를 하나씩 준다. 새 표가 생기면 다음 시작 때 자동으로 받는다.
-- 이 파일은 MySQL에만 적용한다(시험 H2는 db/migration만 쓴다).
DROP PROCEDURE IF EXISTS grant_user_tables;

DELIMITER //
CREATE PROCEDURE grant_user_tables()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE table_to_grant VARCHAR(64);
    DECLARE tables_cursor CURSOR FOR
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_type = 'BASE TABLE'
          AND table_name NOT IN ('admin_account', 'SPRING_SESSION_ADMIN', 'SPRING_SESSION_ADMIN_ATTRIBUTES', '${flyway:table}');
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    OPEN tables_cursor;
    grant_loop: LOOP
        FETCH tables_cursor INTO table_to_grant;
        IF done THEN
            LEAVE grant_loop;
        END IF;
        SET @grant_sql = CONCAT('GRANT SELECT, INSERT, UPDATE, DELETE ON `', DATABASE(), '`.`', table_to_grant,
            '` TO ''${user_db_username}''@''${user_db_host}''');
        PREPARE grant_statement FROM @grant_sql;
        EXECUTE grant_statement;
        DEALLOCATE PREPARE grant_statement;
    END LOOP;
    CLOSE tables_cursor;
END //
DELIMITER ;

CALL grant_user_tables();
DROP PROCEDURE grant_user_tables;
