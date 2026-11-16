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

    // 경도 1도의 길이(m). 적도에서의 값이라 실제 위도에서는 cos(위도)를 곱해 줄여야 한다
    private static final double METERS_PER_DEGREE_LNG_AT_EQUATOR = 111_320.0;
    // 위도 1도의 길이(m). 위도에 따른 변화가 작아 상수로 쓴다
    private static final double METERS_PER_DEGREE_LAT = 110_540.0;

    /**
     * 위도·경도 다각형을 미터 좌표로 펴서 면적을 구한다.
     *
     * @param polygon 경도(x)·위도(y) 좌표의 다각형
     * @param interiorPoint 다각형 안의 점. 이 점의 위도로 경도 1도의 길이를 정한다
     * @return 면적(㎡)
     */
    public double areaM2(Polygon polygon, Point interiorPoint) {
        // 위도가 높을수록 경도 1도가 짧아지므로, 대표 위도의 cos 값으로 경도 1도의 미터 길이를 구한다
        double lngScale = METERS_PER_DEGREE_LNG_AT_EQUATOR * Math.cos(Math.toRadians(interiorPoint.getY()));
        // 원본 다각형(도 단위)은 호출한 쪽이 계속 쓰므로, 복사본의 좌표만 미터로 바꾼다
        Geometry meters = polygon.copy();
        // 좌표를 하나씩 돌며 x·y를 미터로 바꾸는 필터. 바뀐 좌표로 면적을 구하면 평면 면적이 된다
        meters.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence sequence, int i) {
                // x(경도)는 위도 보정 비율을 곱해 미터로 바꾼다
                sequence.setOrdinate(i, Coordinate.X, sequence.getX(i) * lngScale);
                // y(위도)는 고정 비율로 미터로 바꾼다
                sequence.setOrdinate(i, Coordinate.Y, sequence.getY(i) * METERS_PER_DEGREE_LAT);
            }

            @Override
            // 모든 좌표를 처리해야 하므로 중간에 멈추지 않는다
            public boolean isDone() {
                return false;
            }

            @Override
            // 좌표를 바꿨음을 알려, JTS가 도형의 캐시된 범위(envelope) 등을 다시 계산하게 한다
            public boolean isGeometryChanged() {
                return true;
            }
        });
        // 미터 좌표 위의 평면 면적이라 단위가 ㎡다
        return meters.getArea();
    }
}
