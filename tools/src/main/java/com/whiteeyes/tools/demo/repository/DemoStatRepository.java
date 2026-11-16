package com.whiteeyes.tools.demo.repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.whiteeyes.common.stat.entity.StatValues;

/**
 * 시연용 통계(is_demo=1) 행을 넣고(FNC-A-051) 지운다(FNC-A-052). 한 번에 다 지우면 잠금이 길어지므로 정해진 건수씩 나눠 지운다.
 * 1년 치(구역 16개면 시간별 약 14만 건)를 넣으므로 JPA 대신 JDBC 일괄 넣기를 쓴다.
 * 표 이름은 이 클래스 안의 상수만 받으므로 SQL에 이어 붙여도 된다.
 */
@Repository
@RequiredArgsConstructor
public class DemoStatRepository {

    // 지울 수 있는 표 이름. requireDemoTable이 이 두 값만 통과시킨다.
    public static final String HOURLY_TABLE = "zone_stat_hourly";
    public static final String DAILY_TABLE = "zone_stat_daily";

    // 두 표가 공통으로 가진 값 칸 목록(키 칸인 zone_id와 stat_at/stat_date는 따로 붙인다). 마지막 is_demo 칸은 아래에서 상수 1로 채운다.
    private static final String VALUE_COLUMNS = "count_avg, count_max, count_min, density,"
            + " in_n, in_e, in_s, in_w, out_n, out_e, out_s, out_w, dwell_sum_sec, dwell_count, sample_sec, is_demo";
    // VALUE_COLUMNS와 칸 수가 맞는 값 자리: 바인딩할 ? 15개와, is_demo에 넣을 상수 1.
    private static final String VALUE_PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 시연용 행을 최대 limit건 지운다. 문장마다 바로 커밋된다(트랜잭션으로 묶지 않음).
     *
     * @param table {@link #HOURLY_TABLE} 또는 {@link #DAILY_TABLE}
     * @return 지운 건수. limit보다 작으면 더 지울 행이 없다
     */
    public int deleteDemoRows(String table, int limit) {
        // 표 이름은 바인딩할 수 없고 SQL에 직접 이어 붙이므로, 허용된 두 상수인지 먼저 확인한다.
        requireDemoTable(table);
        // DELETE … LIMIT은 MySQL과 H2(MySQL 모드) 모두 지원한다
        // is_demo = 1 조건으로 실제 데이터는 지우지 않는다. 한도(limit)는 값으로 바인딩한다.
        return jdbcTemplate.update("DELETE FROM " + table + " WHERE is_demo = 1 LIMIT ?", limit);
    }

    /**
     * 정해진 구역의 시연용 행만 최대 limit건 지운다(--zone). 문장마다 바로 커밋된다.
     *
     * @param table {@link #HOURLY_TABLE} 또는 {@link #DAILY_TABLE}
     * @param zoneIds 지울 구역 번호. 비어 있으면 안 된다
     * @return 지운 건수. limit보다 작으면 더 지울 행이 없다
     */
    public int deleteDemoRows(String table, Collection<Long> zoneIds, int limit) {
        requireDemoTable(table);
        // 비어 있으면 IN ()이 되어 SQL 오류가 나므로 미리 막는다.
        if (zoneIds.isEmpty()) {
            throw new IllegalArgumentException("지울 구역이 없습니다");
        }
        // 구역 수만큼 ?를 만들어 IN (?, ?, ...)을 구성한다. 번호는 바인딩하므로 SQL 삽입 위험이 없다.
        String placeholders = String.join(", ", Collections.nCopies(zoneIds.size(), "?"));
        // 바인딩 순서는 SQL의 ? 순서와 같다. 구역 번호들 다음에 LIMIT 값이 온다.
        List<Object> args = new ArrayList<>(zoneIds);
        args.add(limit);
        return jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE is_demo = 1 AND zone_id IN (" + placeholders + ") LIMIT ?",
                args.toArray());
    }

    /**
     * 시연용 통계 표 이름이 아니면 예외를 던진다(다른 표를 지우는 사고를 막는다).
     */
    private static void requireDemoTable(String table) {
        if (!HOURLY_TABLE.equals(table) && !DAILY_TABLE.equals(table)) {
            throw new IllegalArgumentException("시연용 통계 표가 아닙니다: " + table);
        }
    }

    /** 시연용 시간별 통계를 is_demo=1로 넣는다. */
    public void insertHourly(List<DemoHourlyStat> rows) {
        // 행을 한 번에 묶어 보내는 JDBC 배치 삽입. 구역이 많으면 14만 건 규모라 건별 INSERT나 JPA보다 훨씬 빠르다.
        jdbcTemplate.batchUpdate(
                "INSERT INTO " + HOURLY_TABLE + " (zone_id, stat_at, " + VALUE_COLUMNS + ") VALUES (?, ?, "
                        + VALUE_PLACEHOLDERS + ")",
                rows,
                rows.size(),
                (statement, row) -> {
                    // 1·2번 칸은 키(구역, 시각)이고, 3번 칸부터는 setValues가 값을 채운다.
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
                    // 1·2번 칸은 키(구역, 날짜)이고, 3번 칸부터는 setValues가 값을 채운다.
                    statement.setLong(1, row.zoneId());
                    statement.setDate(2, Date.valueOf(row.statDate()));
                    setValues(statement, row.values());
                });
    }

    // 키 두 칸(1·2번) 다음부터 값 칸을 채운다
    private static void setValues(PreparedStatement statement, StatValues values) throws SQLException {
        // StatValues의 값을 VALUE_COLUMNS의 칸 순서대로 3번부터 채운다. 순서가 어긋나면 값이 엉뚱한 칸에 들어가므로 두 곳을 함께 고쳐야 한다.
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
