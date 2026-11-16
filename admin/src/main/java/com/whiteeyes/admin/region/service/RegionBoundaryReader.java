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

    // 경계 문자열을 JSON 트리로 읽는 파서. 상태를 갖지 않아 한 번 만들어 재사용한다
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    // JTS 도형을 만드는 팩토리. 기본 설정이라 위도·경도 숫자를 변환 없이 그대로 좌표로 담는다
    private final GeometryFactory geometryFactory = new GeometryFactory();

    /**
     * 행정구역 한 곳의 경계 문자열을 JTS 도형으로 바꾼다.
     *
     * @throws IllegalStateException 저장된 경계가 Polygon·MultiPolygon 형식이 아닐 때
     */
    public Geometry read(Region region) {
        try {
            // 경계 문자열 → JSON 트리 → JTS 도형 순서로 바꾼다
            return toGeometry(jsonMapper.readTree(region.getBoundary()));
        } catch (JacksonException | IllegalArgumentException e) {
            // 적재 단계에서 이미 검증된 값이다. 여기서 깨졌다면 사용자 입력 오류가 아니라 데이터 이상이라 4xx 업무 예외가 아닌 IllegalStateException으로 알리고, 어느 지역인지
            // code를 남긴다
            throw new IllegalStateException("저장된 경계를 읽을 수 없습니다: " + region.getCode(), e);
        }
    }

    /** GeoJSON geometry 객체의 type에 따라 Polygon 또는 MultiPolygon 도형을 만든다. */
    private Geometry toGeometry(JsonNode geometry) {
        // path()는 키가 없어도 null이 아닌 빈 노드를 돌려주므로, 형식이 틀리면 아래 default 분기나 도형 생성 단계에서 걸러진다
        JsonNode coordinates = geometry.path("coordinates");
        return switch (geometry.path("type").asString("")) {
            case "Polygon" -> toPolygon(coordinates);
                // MultiPolygon은 폴리곤 목록이다. 한 행정구역이 떨어진 여러 땅으로 이루어진 경우를 하나의 도형으로 묶는다
            case "MultiPolygon" -> {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode polygon : coordinates) {
                    polygons.add(toPolygon(polygon));
                }
                yield geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
                // 다른 도형 종류는 지원하지 않는다. read()가 IllegalStateException으로 감싸 올린다
            default -> throw new IllegalArgumentException("Polygon/MultiPolygon이 아닙니다");
        };
    }

    /** GeoJSON Polygon의 좌표 고리 목록을 JTS 다각형으로 바꾼다. */
    private Polygon toPolygon(JsonNode rings) {
        // GeoJSON 규칙: 첫 번째 고리가 바깥 경계다
        LinearRing shell = toRing(rings.get(0));
        // 두 번째 고리부터는 다각형 안쪽의 구멍(내부 경계)이다
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = toRing(rings.get(i));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    /** 좌표 배열 하나를 닫힌 고리(LinearRing)로 만든다. 시작점과 끝점이 같지 않으면 JTS가 IllegalArgumentException을 던진다. */
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
