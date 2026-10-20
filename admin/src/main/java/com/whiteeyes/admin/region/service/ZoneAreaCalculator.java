package com.whiteeyes.admin.region.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

/**
 * 위도·경도 다각형의 면적(㎡)을 구한다(FNC-A-013 처리 순서 3).
 *
 * <p>내부 점의 위도 φ0 기준으로 x = 경도 × 111,320 × cos φ0, y = 위도 × 110,540 (m)로 바꾼 뒤 평면 면적을 쓴다.
 * 수백 m 크기 구역에서 오차 1% 미만이다.
 */
@Component
public class ZoneAreaCalculator {

    private static final double METERS_PER_DEGREE_LNG_AT_EQUATOR = 111_320.0;
    private static final double METERS_PER_DEGREE_LAT = 110_540.0;

    public double areaM2(Polygon polygon, Point interiorPoint) {
        double lngScale = METERS_PER_DEGREE_LNG_AT_EQUATOR * Math.cos(Math.toRadians(interiorPoint.getY()));
        Geometry meters = polygon.copy();
        meters.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence sequence, int i) {
                sequence.setOrdinate(i, Coordinate.X, sequence.getX(i) * lngScale);
                sequence.setOrdinate(i, Coordinate.Y, sequence.getY(i) * METERS_PER_DEGREE_LAT);
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        return meters.getArea();
    }
}
