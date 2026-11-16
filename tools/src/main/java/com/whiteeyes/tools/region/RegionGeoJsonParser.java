package com.whiteeyes.tools.region;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.region.entity.RegionLevel;

/**
 * 행정구역 경계 GeoJSON(FeatureCollection)을 읽는다(FNC-A-012).
 *
 * <p>파일에는 읍·면·동 다각형만 있다(결정 0014). properties.adm_cd는 8자리로, 앞 2자리가 시·도, 앞 5자리가
 * 시·군·구다. 읍·면·동 이름은 adm_nm의 마지막 단어이고, 시·도·시·군·구 이름은 sidonm·sggnm이다.
 * 시·도와 시·군·구 행은 코드 앞자리로 만들고 경계는 소속 읍·면·동을 합쳐서(JTS union) 만든다.
 */
public class RegionGeoJsonParser {

    private static final String CODE_PROPERTY = "adm_cd";
    private static final String NAME_PROPERTY = "adm_nm";
    private static final String SIDO_NAME_PROPERTY = "sidonm";
    private static final String SIGUNGU_NAME_PROPERTY = "sggnm";
    private static final int SIDO_CODE_LENGTH = 2;
    private static final int SIGUNGU_CODE_LENGTH = 5;
    private static final int EMD_CODE_LENGTH = 8;
    // 이름 길이 상한. 이보다 길면 적재할 수 없어 미리 거른다.
    private static final int MAX_NAME_LENGTH = 50;
    // 대표 점 좌표를 소수 여섯째 자리까지 쓴다(약 0.1m 정밀도).
    private static final int COORDINATE_SCALE = 6;
    // 경도 ±180, 위도 ±90을 넘으면 WGS84가 아닌 좌표계 파일로 보고 거부한다.
    private static final double MAX_LONGITUDE = 180;
    private static final double MAX_LATITUDE = 90;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    /**
     * GeoJSON 문자열을 읽어 시·도, 시·군·구, 읍·면·동 적재 대상 목록을 만든다.
     *
     * @throws RegionFileFormatException 형식이 틀리거나 코드가 중복되거나 비어 있을 때
     */
    public List<RegionSource> parse(String geoJson) {
        // 읽기 오류를 형식 예외로 바꾸려고 try 안에서 한 번만 대입한다.
        final JsonNode root;
        try {
            root = jsonMapper.readTree(geoJson);
        } catch (JacksonException e) {
            throw new RegionFileFormatException("GeoJSON을 읽을 수 없습니다", e);
        }
        // 최상위가 FeatureCollection이고 features가 배열인지 확인한다.
        if (!"FeatureCollection".equals(root.path("type").asString(null))
                || !root.path("features").isArray()) {
            throw new RegionFileFormatException("FeatureCollection 형식이 아닙니다");
        }

        // 파일의 읍·면·동 하나하나를 Emd로 바꿔 모은다.
        List<Emd> emds = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode feature : root.path("features")) {
            Emd emd = toEmd(feature);
            // 같은 코드가 두 번 나오면 어느 쪽이 맞는지 알 수 없어 멈춘다.
            if (!codes.add(emd.code())) {
                throw new RegionFileFormatException("행정구역 코드가 중복되었습니다: " + emd.code());
            }
            emds.add(emd);
        }
        // 지역이 하나도 없으면 빈 SQL이 되므로 오류로 본다.
        if (emds.isEmpty()) {
            throw new RegionFileFormatException("행정구역이 하나도 없습니다");
        }
        // 읍·면·동을 묶어 상위 단계(시·군·구, 시·도)까지 만든다.
        return toSources(emds);
    }

    /**
     * features의 한 항목(읍·면·동)을 읽어 검사하고 Emd로 바꾼다.
     */
    private Emd toEmd(JsonNode feature) {
        JsonNode properties = feature.path("properties");
        String code = properties.path(CODE_PROPERTY).asString("").trim();
        // adm_cd는 8자리 숫자여야 한다. 앞자리로 시·도·시·군·구를 가리므로 길이가 틀리면 상위 단계를 만들 수 없다.
        if (code.length() != EMD_CODE_LENGTH || !code.chars().allMatch(Character::isDigit)) {
            throw new RegionFileFormatException(CODE_PROPERTY + " 값은 8자리 숫자여야 합니다: " + code);
        }
        String fullName = properties.path(NAME_PROPERTY).asString("").trim();
        if (fullName.isEmpty()) {
            throw new RegionFileFormatException(NAME_PROPERTY + " 값이 없습니다: " + code);
        }
        // adm_nm은 공백으로 이어진 전체 이름이고, 읍·면·동 이름은 그 마지막 단어다.
        String name = checkedName(fullName.substring(fullName.lastIndexOf(' ') + 1), code);
        String sidoName =
                checkedName(properties.path(SIDO_NAME_PROPERTY).asString("").trim(), code);
        String sigunguName =
                checkedName(properties.path(SIGUNGU_NAME_PROPERTY).asString("").trim(), code);

        // 경계 원본 문자열은 그대로 보관하고(boundaryJson), 합치기·대표 점 계산용 도형(JTS)은 따로 만든다.
        JsonNode geometryNode = feature.path("geometry");
        return new Emd(code, name, sidoName, sigunguName, geometryNode.toString(), toGeometry(geometryNode, code));
    }

    /**
     * 이름이 비어 있거나 너무 길면 예외를 던진다. 통과하면 이름을 그대로 돌려준다.
     */
    private String checkedName(String name, String code) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new RegionFileFormatException("이름이 없거나 너무 깁니다: " + code);
        }
        return name;
    }

    /** 읍·면·동을 시·도, 시·군·구로 묶어 세 단위의 적재 대상을 만든다. */
    private List<RegionSource> toSources(List<Emd> emds) {
        // 코드 앞자리(시·도 2자리, 시·군·구 5자리) → 소속 읍·면·동 도형 모음. LinkedHashMap이라 파일에 나온 순서를 유지한다.
        Map<String, Group> sidos = new LinkedHashMap<>();
        Map<String, Group> sigungus = new LinkedHashMap<>();
        // 읍·면·동마다 자기가 속한 시·도, 시·군·구 그룹에 도형을 넣는다.
        for (Emd emd : emds) {
            group(sidos, emd.code().substring(0, SIDO_CODE_LENGTH), emd.sidoName())
                    .geometries
                    .add(emd.geometry());
            group(sigungus, emd.code().substring(0, SIGUNGU_CODE_LENGTH), emd.sigunguName())
                    .geometries
                    .add(emd.geometry());
        }

        // 시·도 → 시·군·구 → 읍·면·동 순으로 담는다. 시·도·시·군·구의 경계는 소속 읍·면·동 도형을 합친 것이다.
        List<RegionSource> sources = new ArrayList<>();
        sidos.forEach((code, group) -> sources.add(toSource(code, null, RegionLevel.SIDO, group)));
        sigungus.forEach((code, group) ->
                sources.add(toSource(code, code.substring(0, SIDO_CODE_LENGTH), RegionLevel.SIGUNGU, group)));
        // 읍·면·동은 자기 경계를 그대로 쓰고 상위는 시·군·구 코드다.
        for (Emd emd : emds) {
            // 대표 점은 도형 안쪽에 있는 점(getInteriorPoint)이라 오목한 모양에서도 경계 밖으로 나가지 않는다. 위도(y)·경도(x) 순서에 유의한다.
            Point interiorPoint = emd.geometry().getInteriorPoint();
            sources.add(new RegionSource(
                    emd.code(),
                    emd.code().substring(0, SIGUNGU_CODE_LENGTH),
                    RegionLevel.EMD,
                    emd.name(),
                    emd.boundaryJson(),
                    scaled(interiorPoint.getY()),
                    scaled(interiorPoint.getX())));
        }
        return sources;
    }

    /**
     * 코드에 해당하는 그룹을 찾아(없으면 만들어) 돌려준다. 같은 코드에 이름이 둘 이상이면 원본이 일관되지 않아 멈춘다.
     */
    private Group group(Map<String, Group> groups, String code, String name) {
        Group group = groups.computeIfAbsent(code, key -> new Group(name));
        if (!group.name.equals(name)) {
            throw new RegionFileFormatException("같은 코드(" + code + ")에 이름이 둘 이상입니다: " + group.name + ", " + name);
        }
        return group;
    }

    /**
     * 그룹의 도형을 합쳐 시·도 또는 시·군·구 한 행을 만든다.
     */
    private RegionSource toSource(String code, String parentCode, RegionLevel level, Group group) {
        // 소속 읍·면·동 경계를 하나로 합친다.
        Geometry merged = union(group.geometries, code);
        Point interiorPoint = merged.getInteriorPoint();
        return new RegionSource(
                code,
                parentCode,
                level,
                group.name,
                toGeoJson(merged, code),
                scaled(interiorPoint.getY()),
                scaled(interiorPoint.getX()));
    }

    /**
     * 도형들을 하나로 합친다. 경계선이 꼬인 도형 때문에 실패하면 buffer(0)으로 바로잡아 한 번 더 시도한다.
     */
    private Geometry union(List<Geometry> geometries, String code) {
        try {
            // 여러 도형을 한 번에 합치는 UnaryUnionOp를 쓴다(하나씩 합치는 것보다 효율적이다).
            return UnaryUnionOp.union(geometries);
        } catch (TopologyException e) {
            // 경계선이 꼬인 원본 도형이 섞여 있으면 buffer(0)으로 바로잡아 다시 합친다
            try {
                return UnaryUnionOp.union(
                        geometries.stream().map(g -> g.buffer(0)).toList());
            } catch (TopologyException retry) {
                throw new RegionFileFormatException("경계를 합칠 수 없습니다: " + code, retry);
            }
        }
    }

    /** 합친 도형을 GeoJSON 문자열로 바꾼다. 합친 결과는 Polygon 또는 MultiPolygon이다. */
    private String toGeoJson(Geometry geometry, String code) {
        StringBuilder json = new StringBuilder();
        if (geometry instanceof Polygon polygon) {
            json.append("{\"type\":\"Polygon\",\"coordinates\":");
            appendPolygon(json, polygon);
        } else if (geometry instanceof MultiPolygon multiPolygon) {
            json.append("{\"type\":\"MultiPolygon\",\"coordinates\":[");
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                appendPolygon(json, (Polygon) multiPolygon.getGeometryN(i));
            }
            json.append(']');
        } else {
            throw new RegionFileFormatException("합친 경계가 다각형이 아닙니다: " + code);
        }
        return json.append('}').toString();
    }

    /**
     * GeoJSON Polygon 좌표 배열을 쓴다: 첫 고리가 바깥 경계이고 이어서 구멍 고리들이 온다.
     */
    private void appendPolygon(StringBuilder json, Polygon polygon) {
        json.append('[');
        appendRing(json, polygon.getExteriorRing().getCoordinates());
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            json.append(',');
            appendRing(json, polygon.getInteriorRingN(i).getCoordinates());
        }
        json.append(']');
    }

    /**
     * 고리 하나를 [[경도,위도], ...] 형태로 쓴다.
     */
    private void appendRing(StringBuilder json, Coordinate[] coordinates) {
        json.append('[');
        for (int i = 0; i < coordinates.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('[')
                    .append(coordinates[i].x)
                    .append(',')
                    .append(coordinates[i].y)
                    .append(']');
        }
        json.append(']');
    }

    /**
     * GeoJSON geometry를 JTS 도형으로 바꾼다. Polygon과 MultiPolygon만 허용하고 비어 있으면 거부한다.
     */
    private Geometry toGeometry(JsonNode geometry, String code) {
        String type = geometry.path("type").asString("");
        JsonNode coordinates = geometry.path("coordinates");
        try {
            Geometry result;
            if ("Polygon".equals(type)) {
                result = toPolygon(coordinates, code);
            } else if ("MultiPolygon".equals(type)) {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode polygon : coordinates) {
                    polygons.add(toPolygon(polygon, code));
                }
                result = geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
            } else {
                throw new RegionFileFormatException("Polygon/MultiPolygon이 아닙니다: " + code);
            }
            if (result.isEmpty()) {
                throw new RegionFileFormatException("경계가 비어 있습니다: " + code);
            }
            return result;
        } catch (IllegalArgumentException e) {
            // JTS가 닫히지 않은 고리처럼 잘못된 좌표를 IllegalArgumentException으로 알리면 코드와 함께 형식 예외로 바꾼다.
            throw new RegionFileFormatException("경계 좌표가 올바르지 않습니다: " + code, e);
        }
    }

    /**
     * 고리 배열을 다각형으로 바꾼다. GeoJSON 규칙에 따라 첫 고리는 바깥 경계, 나머지는 구멍이다.
     */
    private Polygon toPolygon(JsonNode rings, String code) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new RegionFileFormatException("다각형 좌표가 없습니다: " + code);
        }
        LinearRing shell = toRing(rings.get(0), code);
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = toRing(rings.get(i), code);
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    /**
     * 좌표 배열을 닫힌 고리로 바꾼다. 좌표 형식과 위도·경도 범위를 검사한다.
     */
    private LinearRing toRing(JsonNode positions, String code) {
        if (!positions.isArray()) {
            throw new RegionFileFormatException("다각형 좌표 형식이 올바르지 않습니다: " + code);
        }
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < coordinates.length; i++) {
            JsonNode position = positions.get(i);
            if (!position.isArray()
                    || position.size() < 2
                    || !position.get(0).isNumber()
                    || !position.get(1).isNumber()) {
                throw new RegionFileFormatException("좌표 형식이 올바르지 않습니다: " + code);
            }
            // GeoJSON 순서는 [경도, 위도]
            double longitude = position.get(0).asDouble();
            double latitude = position.get(1).asDouble();
            // 좌표계가 다른 파일이면 DB 저장 때 원인을 알기 어려우므로 여기서 먼저 알린다
            if (Math.abs(longitude) > MAX_LONGITUDE || Math.abs(latitude) > MAX_LATITUDE) {
                throw new RegionFileFormatException("위도·경도 범위를 벗어난 좌표입니다(WGS84 경도 ±180, 위도 ±90): " + code + " ["
                        + longitude + ", " + latitude + "]");
            }
            coordinates[i] = new Coordinate(longitude, latitude);
        }
        return geometryFactory.createLinearRing(coordinates);
    }

    /**
     * 좌표를 소수 여섯째 자리로 반올림한다.
     */
    private BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 파일의 읍·면·동 하나. */
    private record Emd(
            String code, String name, String sidoName, String sigunguName, String boundaryJson, Geometry geometry) {}

    /** 같은 시·도 또는 시·군·구에 속한 읍·면·동 도형 모음. */
    private static final class Group {
        private final String name;
        private final List<Geometry> geometries = new ArrayList<>();

        private Group(String name) {
            this.name = name;
        }
    }
}
