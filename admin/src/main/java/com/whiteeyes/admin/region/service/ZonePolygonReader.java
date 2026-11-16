package com.whiteeyes.admin.region.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import com.whiteeyes.admin.region.exception.InvalidPolygonException;

/** 요청의 GeoJSON Polygon을 JTS 다각형으로 바꾸고 유효한지 확인한다(FNC-A-013 처리 순서 1). */
@Component
public class ZonePolygonReader {

    // JTS 도형 생성 팩토리. 위도·경도 숫자를 변환 없이 좌표로 담는다
    private final GeometryFactory geometryFactory = new GeometryFactory();

    /**
     * GeoJSON Polygon을 JTS 다각형으로 바꾸고 검증한다.
     *
     * @throws InvalidPolygonException Polygon이 아니거나 좌표·도형이 올바르지 않을 때
     */
    public Polygon read(JsonNode boundary) {
        // 요청에 boundary가 없거나 Polygon이 아닌 도형(Point, MultiPolygon 등)이면 거부한다
        if (boundary == null || !"Polygon".equals(boundary.path("type").asString(""))) {
            throw new InvalidPolygonException("다각형(Polygon)이 아닙니다.");
        }
        // GeoJSON Polygon의 coordinates는 '고리의 배열'이다(첫 고리가 바깥 경계, 나머지는 구멍)
        JsonNode rings = boundary.path("coordinates");
        if (!rings.isArray() || rings.isEmpty()) {
            throw new InvalidPolygonException("다각형 좌표가 없습니다.");
        }
        try {
            // 바깥 경계 고리
            LinearRing shell = toRing(rings.get(0));
            // 구멍 고리들(없으면 길이 0)
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
            // 고리가 닫히지 않았거나 점이 모자라면 JTS가 IllegalArgumentException을 던진다. 사용자 입력 오류로 바꿔 알린다
        } catch (IllegalArgumentException e) {
            throw new InvalidPolygonException("다각형 좌표가 올바르지 않습니다.", e);
        }
    }

    /** 좌표 배열을 닫힌 고리로 만든다. 좌표가 숫자 두 개 이상인 배열이 아니면 InvalidPolygonException이다. */
    private LinearRing toRing(JsonNode positions) {
        // 고리가 배열이 아니면 형식 오류
        if (!positions.isArray()) {
            throw new InvalidPolygonException("다각형 좌표 형식이 올바르지 않습니다.");
        }
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < coordinates.length; i++) {
            JsonNode position = positions.get(i);
            // 점 하나는 [경도, 위도(, 고도)] 형태의 숫자 배열이어야 한다. 숫자가 아닌 값이 asDouble에서 조용히 0 같은 값으로 읽히지 않도록 먼저 막는다
            if (!position.isArray()
                    || position.size() < 2
                    || !position.get(0).isNumber()
                    || !position.get(1).isNumber()) {
                throw new InvalidPolygonException("좌표 형식이 올바르지 않습니다.");
            }
            // GeoJSON 순서는 [경도, 위도]
            coordinates[i] =
                    new Coordinate(position.get(0).asDouble(), position.get(1).asDouble());
        }
        return geometryFactory.createLinearRing(coordinates);
    }
}
