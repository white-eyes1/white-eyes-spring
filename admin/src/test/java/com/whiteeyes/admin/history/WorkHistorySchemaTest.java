package com.whiteeyes.admin.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5 테이블의 칼럼(이름·타입·길이·NOT NULL)과 인덱스가 tables.md의 work_history와 같은지 DB에서 직접 확인한다.
 * 테스트 DB(H2)에서는 JPA 칼럼 검증(ddl-auto=validate)이 돌지 않으므로, 엔티티와의 일치는 WorkHistoryServiceTest가
 * 엔티티로 저장해 DB에서 읽어 보는 것으로 확인한다. COMMENT는 확인하지 않는다.
 */
@SpringBootTest
class WorkHistorySchemaTest {

    private static final String CHARACTER_VARYING = "CHARACTER VARYING";

    private record ExpectedColumn(String name, String type, Integer length, boolean nullable) {}

    // tables.md work_history의 칼럼 순서·타입·길이·NOT NULL (DATETIME은 H2에서 TIMESTAMP로 보인다)
    private static final List<ExpectedColumn> EXPECTED_COLUMNS = List.of(
            new ExpectedColumn("ID", "BIGINT", null, false),
            new ExpectedColumn("ACTOR_TYPE", CHARACTER_VARYING, 10, false),
            new ExpectedColumn("ACTOR_ID", "BIGINT", null, false),
            new ExpectedColumn("ACTOR_LOGIN_ID", CHARACTER_VARYING, 20, false),
            new ExpectedColumn("ACTION", CHARACTER_VARYING, 30, false),
            new ExpectedColumn("TARGET_TYPE", CHARACTER_VARYING, 20, true),
            new ExpectedColumn("TARGET_ID", "BIGINT", null, true),
            new ExpectedColumn("TARGET_NAME", CHARACTER_VARYING, 100, true),
            new ExpectedColumn("DETAIL", "JSON", null, true),
            new ExpectedColumn("CREATED_AT", "TIMESTAMP", null, false));

    // tables.md work_history 인덱스 4개 (PK 제외)
    private static final List<List<String>> EXPECTED_INDEXES = List.of(
            List.of("CREATED_AT"),
            List.of("ACTOR_TYPE", "ACTOR_LOGIN_ID", "CREATED_AT"),
            List.of("ACTION", "CREATED_AT"),
            List.of("TARGET_TYPE", "TARGET_ID", "CREATED_AT"));

    private final JdbcTemplate jdbcTemplate;

    WorkHistorySchemaTest(@Autowired JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("tables.md work_history 칼럼의 이름·타입·길이·NOT NULL이 V5와 같다")
    void columns_matchTablesSpec() {
        List<ExpectedColumn> actual = jdbcTemplate.query(
                """
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'WORK_HISTORY'
                ORDER BY ORDINAL_POSITION
                """,
                (resultSet, rowNumber) -> new ExpectedColumn(
                        resultSet.getString("COLUMN_NAME").toUpperCase(),
                        resultSet.getString("DATA_TYPE"),
                        // H2는 JSON 칼럼의 길이를 내부 최댓값으로 알려 주므로 길이는 문자열 타입만 본다
                        resultSet.getObject("CHARACTER_MAXIMUM_LENGTH") == null
                                        || "JSON".equals(resultSet.getString("DATA_TYPE"))
                                ? null
                                : resultSet.getInt("CHARACTER_MAXIMUM_LENGTH"),
                        "YES".equals(resultSet.getString("IS_NULLABLE"))));

        assertThat(actual).containsExactlyElementsOf(EXPECTED_COLUMNS);
    }

    @Test
    @DisplayName("tables.md work_history 인덱스 4개의 칼럼 구성이 V5와 같다")
    void indexes_matchTablesSpec() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT INDEX_NAME, COLUMN_NAME, ORDINAL_POSITION
                FROM INFORMATION_SCHEMA.INDEX_COLUMNS
                WHERE UPPER(TABLE_NAME) = 'WORK_HISTORY' AND UPPER(INDEX_NAME) NOT LIKE 'PRIMARY%'
                ORDER BY INDEX_NAME, ORDINAL_POSITION
                """);

        List<List<String>> actual = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.get("INDEX_NAME").toString(),
                        Collectors.mapping(
                                row -> row.get("COLUMN_NAME").toString().toUpperCase(), Collectors.toList())))
                .values()
                .stream()
                .toList();

        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEXES);
    }
}
