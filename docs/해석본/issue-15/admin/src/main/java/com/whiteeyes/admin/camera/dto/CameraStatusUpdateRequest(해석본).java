/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/camera/dto/CameraStatusUpdateRequest.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025), API: PATCH /internal/v1/cameras/{id}/status
 *
 * [무엇] 분석 서버의 상태 보고 본문 {"status": "PREPARING", "progress": 45, "errorMessage": null}을 받는 요청 DTO.
 *   캠 id는 본문이 아니라 URL 경로({id})로 오므로 여기엔 없다.
 * [검증 규칙과 그 이유] (사용자와 합의: 잘못된 값은 400 — FNC-C-027 "검증 오류 → 400")
 *   - status @NotNull: 상태 보고에서 상태가 빠지면 의미가 없다. 타입이 enum이라 "PAUSED" 같은 값은 JSON 변환에서 바로 400.
 *   - progress @Min(0) @Max(100): tables.md "준비 진행률(0~100)". 선택 항목이라 @NotNull은 없다(null이면 기존 값 유지).
 *     int가 아니라 Integer인 이유: int면 안 보냈을 때 0이 들어와 '안 보냄'과 '0%'를 구분할 수 없다.
 *   - errorMessage @Size(max = 500): tables.md error_message VARCHAR(500). 더 길면 DB 저장에서 터지므로 입구에서 막는다.
 * [이름] '{동작}Request' 규칙: 상태(Status)를 갱신(Update)하는 요청.
 */
package com.whiteeyes.admin.camera.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.whiteeyes.common.camera.entity.CameraStatus;

public record CameraStatusUpdateRequest(
        @NotNull CameraStatus status, @Min(0) @Max(100) Integer progress, @Size(max = 500) String errorMessage) {}
