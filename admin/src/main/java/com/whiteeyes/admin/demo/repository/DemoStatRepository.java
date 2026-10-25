package com.whiteeyes.admin.demo.repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.whiteeyes.common.stat.entity.StatValues;

/**
 * 시연용 통계(is_demo=1) 행을 넣고(FNC-A-051) 지운다(FNC-A-052). 한 번에 다 지우면 잠금이 길어지므로 정해진 건수씩 나눠 지운다.
 * 1년 치(구역 16개면 시간별 약 14만 건)를 넣으므로 JPA 대신 JDBC 일괄 넣기를 쓴다.
 * 표 이름은 이 클래스 안의 상수만 받으므로 SQL에 이어 붙여도 된다.
 */
@Repository
public class DemoStatRepository {

    public static final String HOURLY_TABLE = "zone_stat_hourly";
    public static final String DAILY_TABLE = "zone_stat_daily";

    private static final String VALUE_COLUMNS = "count_avg, count_max, count_min, density,"
            + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, dwell_sum_sec, dwell_count, sample_sec, is_demo";
    private static final String VALUE_PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1";

    private final JdbcTemplate jdbcTemplate;

    public DemoStatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 시연용 행을 최대 limit건 지운다. 문장마다 바로 커밋된다(트랜잭션으로 묶지 않음).
     *
     * @param table {@link #HOURLY_TABLE} 또는 {@link #DAILY_TABLE}
     * @return 지운 건수. limit보다 작으면 더 지울 행이 없다
     */
    public int deleteDemoRows(String table, int limit) {
        if (!HOURLY_TABLE.equals(table) && !DAILY_TABLE.equals(table)) {
            throw new IllegalArgumentException("시연용 통계 표가 아닙니다: " + table);
        }
        // DELETE … LIMIT은 MySQL과 H2(MySQL 모드) 모두 지원한다
        return jdbcTemplate.update("DELETE FROM " + table + " WHERE is_demo = 1 LIMIT ?", limit);
    }

    /** 시연용 시간별 통계를 is_demo=1로 넣는다. */
    public void insertHourly(List<DemoHourlyStat> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO " + HOURLY_TABLE + " (zone_id, stat_at, " + VALUE_COLUMNS + ") VALUES (?, ?, "
                        + VALUE_PLACEHOLDERS + ")",
                rows,
                rows.size(),
                (statement, row) -> {
                    statement.setLong(1, row.zoneId());
                    statement.setTimestamp(2, Timestamp.valueOf(row.statAt()));
                    setValues(statement, row.values());
                });
    }

    /** 시연용 일별 통계를 is_demo=1로 넣는다. */
    public void insertDaily(List<DemoDailyStat> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO " + DAILY_TABLE + " (zone_id, stat_date, " + VALUE_COLUMNS + ") VALUES (?, ?, "
                        + VALUE_PLACEHOLDERS + ")",
                rows,
                rows.size(),
                (statement, row) -> {
                    statement.setLong(1, row.zoneId());
                    statement.setDate(2, Date.valueOf(row.statDate()));
                    setValues(statement, row.values());
                });
    }

    // 키 두 칸(1·2번) 다음부터 값 칸을 채운다
    private static void setValues(PreparedStatement statement, StatValues values) throws SQLException {
        statement.setBigDecimal(3, values.countAvg());
        statement.setInt(4, values.countMax());
        statement.setInt(5, values.countMin());
        statement.setBigDecimal(6, values.density());
        statement.setInt(7, values.inN());
        statement.setInt(8, values.inE());
        statement.setInt(9, values.inS());
        statement.setInt(10, values.inW());
        statement.setInt(11, values.outN());
        statement.setInt(12, values.outE());
        statement.setInt(13, values.outS());
        statement.setInt(14, values.outW());
        statement.setLong(15, values.dwellSumSec());
        statement.setInt(16, values.dwellCount());
        statement.setInt(17, values.sampleSec());
    }
}
