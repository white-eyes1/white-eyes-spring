package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.region.dto.ZoneCalculation;
import com.whiteeyes.admin.region.dto.ZonePreviewResponse;
import com.whiteeyes.admin.region.exception.OutOfServiceAreaException;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.repository.RegionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 그린 다각형의 면적·소속 동을 저장 전에 계산한다(FNC-A-013). 구역 등록(FNC-A-014)이 같은 계산을 다시 쓴다. */
@Service
@RequiredArgsConstructor
public class ZonePreviewService {

    private static final int AREA_SCALE = 1;
    private static final int COORDINATE_SCALE = 6;

    private final RegionRepository regionRepository;
    private final ZonePolygonReader polygonReader;
    private final ZoneAreaCalculator areaCalculator;
    private final RegionBoundaryReader boundaryReader;

    @Transactional(readOnly = true)
    public ZonePreviewResponse preview(JsonNode boundary) {
        ZoneCalculation calculation = calculate(boundary);
        return new ZonePreviewResponse(calculation.areaM2(), calculation.dongId(), calculation.dongName());
    }

    /** 검증 → 소속 동 → 면적 → 중심점을 한 번에 계산한다. 구역 등록이 서버 값을 쓰려고 다시 호출한다. */
    @Transactional(readOnly = true)
    public ZoneCalculation calculate(JsonNode boundary) {
        Polygon polygon = polygonReader.read(boundary);
        Point interiorPoint = polygon.getInteriorPoint();
        Region dong = findDongContaining(interiorPoint);
        double areaM2 = BigDecimal.valueOf(areaCalculator.areaM2(polygon, interiorPoint))
                .setScale(AREA_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
        return new ZoneCalculation(
                areaM2,
                dong.getId(),
                dong.getName(),
                scaled(interiorPoint.getY()),
                scaled(interiorPoint.getX()));
    }

    private BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    private Region findDongContaining(Point point) {
        for (Region dong : regionRepository.findByLevel(RegionLevel.EMD)) {
            if (boundaryReader.read(dong).contains(point)) {
                return dong;
            }
        }
        throw new OutOfServiceAreaException("서비스 지역 밖입니다.");
    }
}
