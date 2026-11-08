package com.whiteeyes.tools.region;

import java.util.Comparator;
import java.util.List;

/**
 * 적재할 지역 목록을 Flyway SQL로 쓴다(FNC-A-012). code가 같은 행은 덮어써서 다시 적용해도 중복이 없고 id가 유지된다.
 * 상위 지역(시·도 → 시·군·구 → 읍·면·동)부터 쓰고, parent_id는 상위 code로 찾는다.
 * MySQL용과 시험용 H2(MySQL 모드)용을 나눠 쓴다.
 */
public final class RegionSqlWriter {

    private static final String COLUMNS = "parent_id, level, code, name, boundary, center_lat, center_lng";
    // MySQL 8.0.20부터 VALUES()는 없어질 문법이라 행 별칭(AS new)을 쓴다. H2(MySQL 모드)는 행 별칭을 몰라 VALUES()를 쓴다
    private static final String MYSQL_UPDATE = " AS new ON DUPLICATE KEY UPDATE parent_id = new.parent_id,"
            + " level = new.level, name = new.name, boundary = new.boundary, center_lat = new.center_lat,"
            + " center_lng = new.center_lng;";
    private static final String H2_UPDATE =
            " ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), level = VALUES(level),"
                    + " name = VALUES(name), boundary = VALUES(boundary), center_lat = VALUES(center_lat),"
                    + " center_lng = VALUES(center_lng);";

    private RegionSqlWriter() {}

    /**
     * @param header 파일 머리말(출처·생성 명령). 줄마다 SQL 주석으로 쓴다
     * @param h2 시험용 H2(MySQL 모드)에 맞춰 쓴다. 경계 문자열을 JSON 값으로 읽게 하고(FORMAT JSON) VALUES()를 쓴다
     */
    public static String write(List<String> header, List<RegionSource> sources, boolean h2) {
        String jsonSuffix = h2 ? " FORMAT JSON" : "";
        StringBuilder sql = new StringBuilder();
        header.forEach(line -> sql.append("-- ").append(line).append('\n'));
        sql.append('\n');
        List<RegionSource> ordered = sources.stream()
                .sorted(Comparator.comparing(RegionSource::level).thenComparing(RegionSource::code))
                .toList();
        for (RegionSource source : ordered) {
            String parent = "NULL";
            if (source.parentCode() != null) {
                sql.append("SET @parent_id = (SELECT id FROM region WHERE code = ")
                        .append(text(source.parentCode()))
                        .append(");\n");
                parent = "@parent_id";
            }
            sql.append("INSERT INTO region (")
                    .append(COLUMNS)
                    .append(") VALUES (")
                    .append(parent)
                    .append(", ")
                    .append(text(source.level().name()))
                    .append(", ")
                    .append(text(source.code()))
                    .append(", ")
                    .append(text(source.name()))
                    .append(", ")
                    .append(text(source.boundary()))
                    .append(jsonSuffix)
                    .append(", ")
                    .append(source.centerLat().toPlainString())
                    .append(", ")
                    .append(source.centerLng().toPlainString())
                    .append(')')
                    .append(h2 ? H2_UPDATE : MYSQL_UPDATE)
                    .append('\n');
        }
        return sql.toString();
    }

    // MySQL은 \를 이스케이프로 읽고 H2는 읽지 않아 결과가 달라지므로 받지 않는다(경계·이름에는 나오지 않는다)
    private static String text(String value) {
        if (value.indexOf('\\') >= 0) {
            throw new RegionFileFormatException("역슬래시가 든 값은 SQL로 쓸 수 없습니다: " + value);
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
