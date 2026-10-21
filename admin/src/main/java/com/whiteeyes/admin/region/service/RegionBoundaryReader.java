package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.region.exception.RegionFileFormatException;
import com.whiteeyes.common.region.entity.Region;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;

/** region.boundary(GeoJSON 문자열)를 JTS 도형으로 읽는다. 적재 때 검증을 거친 값이라 읽기 실패는 데이터 이상이다. */
@Component
@RequiredArgsConstructor
public class RegionBoundaryReader {

    private final RegionGeoJsonParser geoJsonParser;

    public Geometry read(Region region) {
        try {
            return geoJsonParser.readGeometry(region.getBoundary(), region.getCode());
        } catch (RegionFileFormatException e) {
            throw new IllegalStateException("저장된 경계를 읽을 수 없습니다: " + region.getCode(), e);
        }
    }
}
