package com.whiteeyes.admin.region.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import com.whiteeyes.admin.region.dto.ZoneCalculation;
import com.whiteeyes.admin.region.dto.ZonePreviewResponse;
import com.whiteeyes.admin.region.exception.OutOfServiceAreaException;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.repository.RegionRepository;

/** 그린 다각형의 면적·소속 동을 저장 전에 계산한다(FNC-A-013). 구역 등록(FNC-A-014)이 같은 계산을 다시 쓴다. */
@Service
@RequiredArgsConstructor
public class ZonePreviewService {

    // 미리보기 면적은 소수 첫째 자리까지 보여 준다(등록 시 저장값은 소수 2자리, ZoneRegisterService)
    private static final int AREA_SCALE = 1;
    // 중심 좌표는 소수 6자리(약 0.1m 단위)까지 저장한다
    private static final int COORDINATE_SCALE = 6;

    // 소속 읍·면·동을 찾으려고 읍·면·동 단계 지역을 읽는다
    private final RegionRepository regionRepository;
    // 요청 GeoJSON → JTS 다각형(검증 포함)
    private final ZonePolygonReader polygonReader;
    // 다각형 면적(㎡) 계산
    private final ZoneAreaCalculator areaCalculator;
    // 읍·면·동 경계 → JTS 도형
    private final RegionBoundaryReader boundaryReader;

    @Transactional(readOnly = true)
    /** 계산 결과 중 화면에 필요한 면적·소속 동만 골라 돌려준다. */
    public ZonePreviewResponse preview(JsonNode boundary) {
        ZoneCalculation calculation = calculate(boundary);
        return new ZonePreviewResponse(calculation.areaM2(), calculation.dongId(), calculation.dongName());
    }

    /** 검증 → 소속 동 → 면적 → 중심점을 한 번에 계산한다. 구역 등록이 서버 값을 쓰려고 다시 호출한다. */
    @Transactional(readOnly = true)
    public ZoneCalculation calculate(JsonNode boundary) {
        // 처리 순서 1: 도형이 올바른지 검증하면서 다각형으로 바꾼다
        Polygon polygon = polygonReader.read(boundary);
        // 다각형 '안에' 있음이 보장되는 대표 점. 무게중심은 오목한 도형에서 밖에 놓일 수 있어 쓰지 않는다. 소속 동 판정, 위도 보정, 중심 좌표에 모두 쓴다
        Point interiorPoint = polygon.getInteriorPoint();
        // 처리 순서 2: 이 점을 품은 읍·면·동이 소속 동이다(없으면 서비스 지역 밖 예외)
        Region dong = findDongContaining(interiorPoint);
        // 처리 순서 3: 면적을 구해 소수 1자리로 반올림한다(BigDecimal로 계산해 이진수 오차를 피함)
        double areaM2 = BigDecimal.valueOf(areaCalculator.areaM2(polygon, interiorPoint))
                .setScale(AREA_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
        return new ZoneCalculation(
                areaM2, dong.getId(), dong.getName(), scaled(interiorPoint.getY()), scaled(interiorPoint.getX()));
    }

    /** 좌표를 DB 컬럼 자릿수(소수 6자리)에 맞춰 반올림한다. */
    private BigDecimal scaled(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 점이 들어 있는 읍·면·동을 찾는다.
     *
     * @throws OutOfServiceAreaException 어느 읍·면·동에도 속하지 않을 때
     */
    private Region findDongContaining(Point point) {
        // 읍·면·동 단계 지역을 하나씩 열어 경계 안에 점이 있는지 본다. 동마다 경계를 읽어 비교하는 단순한 방식이다
        for (Region dong : regionRepository.findByLevel(RegionLevel.EMD)) {
            if (boundaryReader.read(dong).contains(point)) {
                return dong;
            }
        }
        throw new OutOfServiceAreaException("서비스 지역 밖입니다.");
    }
}
