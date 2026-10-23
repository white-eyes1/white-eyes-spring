package com.whiteeyes.admin.demo.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 시연용 통계(is_demo=1) 행을 지운다(FNC-A-052). 한 번에 다 지우면 잠금이 길어지므로 정해진 건수씩 나눠 지운다.
 * 표 이름은 이 클래스 안의 상수만 받으므로 SQL에 이어 붙여도 된다.
 */
@Repository
public class DemoStatRepository {

    public static final String HOURLY_TABLE = "zone_stat_hourly";
    public static final String DAILY_TABLE = "zone_stat_daily";

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
}
