/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/exception/RecordingRegisterException.java
 * 관련 이슈: #17(FNC-A-026 예외 "경로 규칙 위반: 400") — TODO(FNC-C-027) 임시 처리
 *
 * [무엇] 녹화 등록의 업무 규칙(경로 규칙, endedAt 규칙)을 어겼을 때 서비스가 던지는 예외. 컨트롤러 밖으로 나가면 400이 된다.
 * [왜 예외 하나로 묶었나] 경로 위반과 endedAt 위반은 둘 다 '요청 내용이 규칙에 안 맞음 → 400'으로 처리가 같다.
 *   종류마다 클래스를 만들면 파일만 늘어난다. 무엇을 어겼는지는 메시지로 구분한다.
 * [왜 임시인가] CameraNotFoundException과 같은 이유. 공통 오류 처리(FNC-C-027)가 생기면 ErrorCode와 공통 응답 형식
 *   ({"code", "message"})으로 바꾼다. 지금은 상태 코드(400)만 맞춰 두었다.
 */
package com.whiteeyes.admin.media.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// TODO(FNC-C-027): 공통 오류 처리가 생기면 BusinessException·ErrorCode로 바꾼다
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RecordingRegisterException extends RuntimeException {

    // [해석] 메시지는 화면에 그대로 띄울 수 있는 한국어 문장으로 쓴다(conventions.md '오류 응답'의 message 규칙에 미리 맞춤).
    public RecordingRegisterException(String message) {
        super(message);
    }
}
