package com.whiteeyes.admin.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * 현재 스레드가 준비한 SQL 수만 센다(FNC-A-036 SQL 수 시험).
 * Hibernate 통계는 SessionFactory 전체를 세어, 같은 앱에서 도는 예약 작업(캠 이상 감지 등)의 쿼리가 섞여 시험이 가끔 실패했다.
 * MockMvc 요청은 시험 스레드에서 실행되므로 이 스레드 몫만 세면 요청 하나의 쿼리 수가 된다.
 * 사용: 시험 속성 {@code spring.jpa.properties.hibernate.session_factory.statement_inspector}에 이 클래스 이름을 준다.
 */
public class ThreadStatementCounter implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static int count() {
        return COUNT.get()[0];
    }

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }
}
