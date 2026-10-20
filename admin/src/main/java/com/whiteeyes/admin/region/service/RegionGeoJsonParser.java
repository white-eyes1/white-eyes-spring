package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.region.dto.RegionSource;
import com.whiteeyes.admin.region.exception.RegionFileFormatException;
import com.whiteeyes.common.region.entity.RegionLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 행정구역 경계 GeoJSON(FeatureCollection)을 읽는다(FNC-A-012).
 *
 * <p>코드 체계는 통계청 SGIS 경계 파일 기준이다: properties.adm_cd의 자릿수로 단위를 정하고
 * (2=시, 5=구, 7=동) 앞 자리가 상위 코드다. properties.adm_nm은 마지막 단어를 이름으로 쓴다.
 */
@Component
public class RegionGeoJsonParser {

    private static final String CODE_PROPERTY = "adm_cd";
    private static final String NAME_PROPERTY = "adm_nm";
    private static final int SI_CODE_LENGTH = 2;
    private static final int GU_CODE_LENGTH = 5;
    private static final int DONG_CODE_LENGTH = 7;
    private static final int MAX_CODE_LENGTH = 10;
    private static final int MAX_NAME_LENGTH = 50;
    private static final int COORDINATE_SCALE = 6;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public List<RegionSource> parse(String geoJson) {
        final JsonNode root;
        try {
            root = jsonMapper.readTree(geoJson);
        } catch (JacksonException e) {
            throw new RegionFileFormatException("GeoJSON을 읽을 수 없습니다", e);
        }
        if (!"FeatureCollection".equals(root.path("type").asString(null))
                || !root.path("features").isArray()) {
            throw new RegionFileFormatException("FeatureCollection 형식이 아닙니다");
        }

        List<RegionSource> sources = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode feature : root.path("features")) {
            RegionSource source = toSource(feature);
            if (!codes.add(source.code())) {
                throw new RegionFileFormatException("행정구역 코드가 중복되었습니다: " + source.code());
            }
            sources.add(source);
        }
        if (sources.isEmpty()) {
            throw new RegionFileFormatException("행정구역이 하나도 없습니다");
        }
        validateParents(sources, codes);
        return sources;
    }

    /** 저장된 region.boundary(Polygon/MultiPolygon GeoJSON 문자열)를 도형으로 읽는다. 구역 미리보기(FNC-A-013)가 쓴다. */
    public Geometry readGeometry(String geometryJson, String code) {
        try {
            return toGeometry(jsonMapper.readTree(geometryJson), code);
        } catch (JacksonException e) {
            throw new RegionFileFormatException("경계를 읽을 수 없습니다: " + code, e);
        }
    }

    private RegionSource toSource(JsonNode feature) {
        JsonNode properties = feature.path("properties");
        String code = properties.path(CODE_PROPERTY).asString("").trim();
        String fullName = properties.path(NAME_PROPERTY).asString("").trim();
        if (code.isEmpty() || code.length() > MAX_CODE_LENGTH || !code.chars().allMatch(Character::isDigit)) {
            throw new RegionFileFormatException(CODE_PROPERTY + " 값이 올바르지 않습니다: " + code);
        }
        if (fullName.isEmpty()) {
            throw new RegionFileFormatException(NAME_PROPERTY + " 값이 없습니다: " + code);
        }
        String name = fullName.substring(fullName.lastIndexOf(' ') + 1);
        if (name.length() > MAX_NAME_LENGTH) {
            throw new RegionFileFormatException("이름이 너무 깁니다: " + code);
        }

        RegionLevel level = levelOf(code);
        JsonNode geometryNode = feature.path("geometry");
        Point interiorPoint = toGeometry(geometryNode, code).getInteriorPoint();
        return new RegionSource(
                code,
                parentCodeOf(code, level),
                level,
                name,
                geometryNode.toString(),
                scaled(interiorPoint.getY()),
                scaled(interiorPoint.getX()));
    }

    private RegionLevel levelOf(String code) {
        return switch (code.length()) {
            case SI_CODE_LENGTH -> RegionLevel.SI;
            case GU_CODE_LENGTH -> RegionLevel.GU;
            case DONG_CODE_LENGTH -> RegionLevel.DONG;
            default -> throw new RegionFileFormatException("코드 자릿수로 시·구·동을 알 수 없습니다: " + code);
        };
    }

    private String parentCodeOf(String code, RegionLevel level) {
        return switch (level) {
            case SI -> null;
            case GU -> code.substring(0, SI_CODE_LENGTH);
            case DONG -> code.substring(0, GU_CODE_LENGTH);
        };
    }

    private void validateParents(List<RegionSource> sources, Set<String> codes) {
        for (RegionSource source : sources) {
            if (source.parentCode() != null && !codes.contains(source.parentCode())) {
                throw new RegionFileFormatException(
                        "상위 행정구역이 파일에 없습니다: " + source.code() + " → " + source.parentCode());
            }
        }
    }

    private Geometry toGeometry(JsonNode geometry, String code) {
        String type = geometry.path("type").asString("");
        JsonNode coordinates = geometry.path("coordinates");
        try {
            Geometry result;
            if ("Polygon".equals(type)) {
                result = toPolygon(coordinates);
            } else if ("MultiPolygon".equals(type)) {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode polygon : coordinates) {
                    polygons.add(toPolygon(polygon));
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
            throw new RegionFileFormatException("경계 좌표가 올바르지 않습니다: " + code, e);
        }
    }

    private Polygon toPolygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new RegionFileFormatException("다각형 좌표가 없습니다");
        }
        LinearRing shell = toRing(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = toRing(rings.get(i));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private LinearRing toRing(JsonNode positions) {
        if (!positions.isArray()) {
            throw new RegionFileFormatException("다각형 좌표 형식이 올바르지 않습니다");
        }
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < coordinates.length; i++) {
            JsonNode position = positions.get(i);
            if (!position.isArray()
                    || position.size() < 2
                    || !position.get(0).isNumber()
                    || !position.get(1).isNumber()) {
                throw new RegionFileFormatException("좌표 형식이 올바르지 않습니다");
            }
            // GeoJSON 순서는 [경도, 위도]
            coordinates[i] = new Coordinate(position.get(0).asDouble(), position.get(1).asDouble());
        }
        return geometryFactory.createLinearRing(coordinates);
    }

    private BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
