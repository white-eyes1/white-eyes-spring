package com.whiteeyes.admin.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3 테이블의 칼럼(이름·타입·길이·NOT NULL)과 기본값이 tables.md의 admin_account와 같은지 DB에서 직접 확인한다.
 * 테스트 DB(H2)가 TINYINT(1)을 boolean으로 보지 못해 JPA 칼럼 검증(ddl-auto=validate)을 켜지 못하므로 이 테스트로 대신한다.
 * 인덱스·외래키·COMMENT는 확인하지 않는다(아이디 중복 거부는 SuperAdminInitializerTest가 확인).
 */
@SpringBootTest
class AdminAccountSchemaTest {

    private static final String CHARACTER_VARYING = "CHARACTER VARYING";

    private record ExpectedColumn(String name, String type, Integer length, boolean nullable) {}

    // tables.md admin_account의 칼럼 순서·타입·길이·NOT NULL (DATETIME은 H2에서 TIMESTAMP로 보인다)
    private static final List<ExpectedColumn> EXPECTED_COLUMNS = List.of(
            new ExpectedColumn("ID", "BIGINT", null, false),
            new ExpectedColumn("LOGIN_ID", CHARACTER_VARYING, 20, false),
            new ExpectedColumn("PASSWORD", CHARACTER_VARYING, 60, false),
            new ExpectedColumn("NAME", CHARACTER_VARYING, 50, false),
            new ExpectedColumn("EMAIL", CHARACTER_VARYING, 100, true),
            new ExpectedColumn("PHONE", CHARACTER_VARYING, 20, true),
            new ExpectedColumn("ROLE", CHARACTER_VARYING, 20, false),
            new ExpectedColumn("STATUS", CHARACTER_VARYING, 20, false),
            new ExpectedColumn("MUST_CHANGE_PASSWORD", "TINYINT", null, false),
            new ExpectedColumn("LAST_LOGIN_AT", "TIMESTAMP", null, true),
            new ExpectedColumn("CREATED_BY", "BIGINT", null, true),
            new ExpectedColumn("CREATED_AT", "TIMESTAMP", null, false),
            new ExpectedColumn("UPDATED_AT", "TIMESTAMP", null, false));

    private final JdbcTemplate jdbcTemplate;

    AdminAccountSchemaTest(@Autowired JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("tables.md admin_account 칼럼의 이름·타입·길이·NOT NULL이 V3와 같다")
    void columns_matchTablesSpec() {
        List<ExpectedColumn> actual = jdbcTemplate.query(
                """
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'ADMIN_ACCOUNT'
                ORDER BY ORDINAL_POSITION
                """,
                (resultSet, rowNumber) -> new ExpectedColumn(
                        resultSet.getString("COLUMN_NAME").toUpperCase(),
                        resultSet.getString("DATA_TYPE"),
                        resultSet.getObject("CHARACTER_MAXIMUM_LENGTH") == null
                                ? null
                                : resultSet.getInt("CHARACTER_MAXIMUM_LENGTH"),
                        "YES".equals(resultSet.getString("IS_NULLABLE"))));

        assertThat(actual).containsExactlyElementsOf(EXPECTED_COLUMNS);
    }

    @Test
    @DisplayName("tables.md admin_account 기본값: role=ADMIN, status=ACTIVE, must_change_password=1")
    void defaults_matchTablesSpec() {
        String loginId = "schema-check";
        jdbcTemplate.update("INSERT INTO admin_account (login_id, password, name) VALUES (?, ?, ?)", loginId, "x", "y");
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT role, status, must_change_password FROM admin_account WHERE login_id = ?", loginId);

            assertThat(row.get("ROLE")).isEqualTo("ADMIN");
            assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
            assertThat(((Number) row.get("MUST_CHANGE_PASSWORD")).intValue()).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM admin_account WHERE login_id = ?", loginId);
        }
    }
}
