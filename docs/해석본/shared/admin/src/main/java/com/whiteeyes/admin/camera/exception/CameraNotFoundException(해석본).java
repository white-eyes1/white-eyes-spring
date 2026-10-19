/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/camera/exception/CameraNotFoundException.java (feature/15-cam-status-report 브랜치)
 * 관련 이슈: #15(FNC-A-025 예외 "없는 캠: 404"), #17(FNC-A-026 예외 "없는 캠: 404")에서 같이 씀 — TODO(FNC-C-027) 임시 처리
 *   (#15·#17 브랜치에서 같은 내용으로 유지)
 *
 * [무엇] 보고 대상 캠이 없거나(삭제 포함) 할 때 서비스가 던지는 예외. 컨트롤러까지 올라가 404 응답이 된다.
 * [왜 예외로 처리하나] 서비스는 HTTP를 모른다(package-structure 3-1 "service는 HTTP 요청·응답 객체를 쓰지 않음").
 *   그래서 서비스는 "캠이 없다"는 사실만 예외로 알리고, 그것을 404로 바꾸는 일은 웹 계층(@ResponseStatus)이 한다.
 * [왜 임시인가] 원래는 공통 오류 처리(FNC-C-027)의 BusinessException + ErrorCode(CAMERA_NOT_FOUND)로 만들어
 *   {"code": "...", "message": "..."} 형식(conventions.md '오류 응답')으로 내보내야 한다. 그 공통 코드가 아직 dev에 없어
 *   @ResponseStatus로 상태 코드만 맞춰 두었다. 지금 응답 본문은 Spring 기본 형식이다(원격 브랜치 feature/1-error-handling 참고).
 */
package com.whiteeyes.admin.camera.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// TODO(FNC-C-027): 공통 오류 처리가 생기면 BusinessException·ErrorCode(CAMERA_NOT_FOUND)로 바꾼다
// [해석] 이 예외가 컨트롤러 밖으로 나가면 Spring이 404 Not Found로 응답한다.
@ResponseStatus(HttpStatus.NOT_FOUND)
// [해석] RuntimeException(언체크 예외): 메서드마다 throws를 적지 않아도 된다. '없는 캠'은 호출한 쪽이 복구할 일이 아니라
//        요청 자체를 실패시키면 되는 상황이라 체크 예외로 강제할 이유가 없다.
public class CameraNotFoundException extends RuntimeException {

    // [해석] 메시지에 cameraId를 넣어 로그·디버깅 때 어느 캠인지 바로 보이게 한다. id만 넣고 개인 정보는 없다(7-4).
    public CameraNotFoundException(Long cameraId) {
        super("캠을 찾을 수 없습니다. cameraId=" + cameraId);
    }
}
