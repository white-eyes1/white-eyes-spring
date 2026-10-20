package com.whiteeyes.admin.region.controller;

import com.whiteeyes.admin.region.exception.InvalidPolygonException;
import com.whiteeyes.admin.region.exception.OutOfServiceAreaException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 구역 API의 오류를 {code, message}로 바꾼다.
 *
 * <p>공통 오류 처리(FNC-C-027)가 common에 들어오면 ErrorCode로 옮기고 이 클래스는 지운다.
 */
@RestControllerAdvice(assignableTypes = ZoneApiController.class)
public class ZoneApiExceptionHandler {

    @ExceptionHandler(InvalidPolygonException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPolygon(InvalidPolygonException e) {
        return badRequest("INVALID_POLYGON", e.getMessage());
    }

    @ExceptionHandler(OutOfServiceAreaException.class)
    public ResponseEntity<Map<String, String>> handleOutOfServiceArea(OutOfServiceAreaException e) {
        return badRequest("OUT_OF_SERVICE_AREA", e.getMessage());
    }

    private ResponseEntity<Map<String, String>> badRequest(String code, String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", code, "message", message));
    }
}
