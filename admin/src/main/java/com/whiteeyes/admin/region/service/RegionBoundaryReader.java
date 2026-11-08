package com.whiteeyes.admin.region.service;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.whiteeyes.common.region.entity.Region;

/**
 * 저장된 region.boundary(Polygon·MultiPolygon GeoJSON)를 도형으로 읽는다. 구역 미리보기(FNC-A-013)가 쓴다.
 * 경계는 행정구역 적재(FNC-A-012, tools region-sql)가 검증해 넣은 값이라 여기서는 형식만 읽는다.
 */
@Component
public class RegionBoundaryReader {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public Geometry read(Region region) {
        try {
            return toGeometry(jsonMapper.readTree(region.getBoundary()));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("저장된 경계를 읽을 수 없습니다: " + region.getCode(), e);
        }
    }

    private Geometry toGeometry(JsonNode geometry) {
        JsonNode coordinates = geometry.path("coordinates");
        return switch (geometry.path("type").asString("")) {
            case "Polygon" -> toPolygon(coordinates);
            case "MultiPolygon" -> {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode polygon : coordinates) {
                    polygons.add(toPolygon(polygon));
                }
                yield geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
            default -> throw new IllegalArgumentException("Polygon/MultiPolygon이 아닙니다");
        };
    }

    private Polygon toPolygon(JsonNode rings) {
        LinearRing shell = toRing(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = toRing(rings.get(i));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    // GeoJSON 순서는 [경도, 위도]
    private LinearRing toRing(JsonNode positions) {
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < coordinates.length; i++) {
            JsonNode position = positions.get(i);
            coordinates[i] =
                    new Coordinate(position.get(0).asDouble(), position.get(1).asDouble());
        }
        return geometryFactory.createLinearRing(coordinates);
    }
}
