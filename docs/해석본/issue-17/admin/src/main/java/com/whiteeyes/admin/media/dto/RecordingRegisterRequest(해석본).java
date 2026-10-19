/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/dto/RecordingRegisterRequest.java
 * 관련 이슈: #17(FNC-A-026), API: POST /internal/v1/recordings (api/analyzer.md)
 *
 * [무엇] 녹화 등록 요청 본문을 받는 요청 DTO. 계약 예시:
 *   {"cameraId": 7, "startedAt": "2026-10-12T14:03:00.000+09:00", "endedAt": "2026-10-12T14:04:00.000+09:00",
 *    "videoPath": "recordings/live/cam7/20261012/cam7_20261012_1403.mp4", "boxPath": "…json.gz"}
 * [검증을 두 곳으로 나눈 이유]
 *   - 여기(DTO): 캠 종류와 상관없이 항상 같은 규칙 — 필수 값, 빈 문자열, 길이(255, tables.md VARCHAR(255))
 *   - 서비스: 캠 종류(LIVE·DUMMY)를 DB에서 알아야 판단할 수 있는 규칙 — endedAt 필수 여부, 경로가 어느 폴더 아래인지
 *   DTO 어노테이션은 DB를 볼 수 없으므로, DB가 필요한 규칙은 서비스로 넘겼다.
 * [이름] '{동작}Request' 규칙: 녹화를 등록(Register)하는 요청.
 */
package com.whiteeyes.admin.media.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// endedAt은 더미 캠이면 없고 라이브 캠이면 필수다. 캠의 입력 방식에 따라 달라서 서비스에서 확인한다
// [해석] 그래서 endedAt에만 @NotNull이 없다.
//        시각은 OffsetDateTime으로 받는다: 계약 형식이 ISO-8601 + 오프셋(+09:00)이고, 밀리초(.000)까지 그대로 받을 수 있다.
//        경로는 @NotBlank: null뿐 아니라 ""나 "  "도 거절한다(빈 경로는 파일을 가리키지 못한다).
public record RecordingRegisterRequest(
        @NotNull Long cameraId,
        @NotNull OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        @NotBlank @Size(max = 255) String videoPath,
        @NotBlank @Size(max = 255) String boxPath) {}
