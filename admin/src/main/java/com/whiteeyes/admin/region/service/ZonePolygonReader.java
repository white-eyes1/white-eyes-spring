package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.region.exception.InvalidPolygonException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 요청의 GeoJSON Polygon을 JTS 다각형으로 바꾸고 유효한지 확인한다(FNC-A-013 처리 순서 1). */
@Component
public class ZonePolygonReader {

    private final GeometryFactory geometryFactory = new GeometryFactory();

    public Polygon read(JsonNode boundary) {
        if (boundary == null || !"Polygon".equals(boundary.path("type").asString(""))) {
            throw new InvalidPolygonException("다각형(Polygon)이 아닙니다.");
        }
        JsonNode rings = boundary.path("coordinates");
        if (!rings.isArray() || rings.isEmpty()) {
            throw new InvalidPolygonException("다각형 좌표가 없습니다.");
        }
        try {
            LinearRing shell = toRing(rings.get(0));
            LinearRing[] holes = new LinearRing[rings.size() - 1];
            for (int i = 1; i < rings.size(); i++) {
                holes[i - 1] = toRing(rings.get(i));
            }
            Polygon polygon = geometryFactory.createPolygon(shell, holes);
            // 점 3개 이상(닫힌 고리 4점)은 LinearRing 생성에서, 자기 교차는 isValid에서 걸러진다
            if (polygon.isEmpty() || !polygon.isValid()) {
                throw new InvalidPolygonException("다각형이 올바르지 않습니다.");
            }
            return polygon;
        } catch (IllegalArgumentException e) {
            throw new InvalidPolygonException("다각형 좌표가 올바르지 않습니다.", e);
        }
    }

    private LinearRing toRing(JsonNode positions) {
        if (!positions.isArray()) {
            throw new InvalidPolygonException("다각형 좌표 형식이 올바르지 않습니다.");
        }
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < coordinates.length; i++) {
            JsonNode position = positions.get(i);
            if (!position.isArray()
                    || position.size() < 2
                    || !position.get(0).isNumber()
                    || !position.get(1).isNumber()) {
                throw new InvalidPolygonException("좌표 형식이 올바르지 않습니다.");
            }
            // GeoJSON 순서는 [경도, 위도]
            coordinates[i] = new Coordinate(position.get(0).asDouble(), position.get(1).asDouble());
        }
        return geometryFactory.createLinearRing(coordinates);
    }
}
