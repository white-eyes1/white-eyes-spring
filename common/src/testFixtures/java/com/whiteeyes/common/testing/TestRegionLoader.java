package com.whiteeyes.common.testing;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * 시험용 행정구역(서울 종로구 3개 동)을 넣는다. 운영 적재(FNC-A-012)와 같은 SQL 형식이다.
 * SQL은 tools의 region-sql 명령으로 admin 시험 자료(region/regions.geojson)에서 만든다(--h2).
 */
@Component
public class TestRegionLoader {

    private static final String SQL = "region/test-regions.sql";

    private final DataSource dataSource;

    public TestRegionLoader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void load() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(SQL));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }
}
