/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/camera/entity/CameraStatus.java
 * 관련 이슈: #15, #16 — TODO(#11) 임시 코드(두 브랜치에서 같은 내용)
 *
 * [무엇] 캠의 실제 상태 4가지. conventions.md '코드 값'의 camera.status와 같은 철자.
 * [왜 enum인가] 문자열("RUNNING")로 다루면 오타("RUNING")를 컴파일러가 못 잡는다. enum이면
 *   - 요청 JSON에 정의되지 않은 값("PAUSED")이 오면 변환 단계에서 바로 400이 난다(#15 컨트롤러 테스트로 확인)
 *   - 비교를 request.status() == CameraStatus.RUNNING처럼 안전하게 할 수 있다(code-conventions 3-3)
 * [염두에 둔 것] 철자는 tables.md·conventions.md와 반드시 같아야 한다(code-conventions 2-1 "명세의 용어를 그대로").
 *   Camera 엔티티가 EnumType.STRING으로 저장하므로 이 이름이 곧 DB에 들어가는 값이다.
 */
package com.whiteeyes.common.camera.entity;

// TODO(#11): 캠 등록(FNC-A-018)에서 정식으로 만든다. FNC-A-025 구현을 위해 먼저 둔 임시 코드
public enum CameraStatus {
    // [해석] 정지(관리자가 멈췄거나 아직 가동 전)
    STOPPED,
    // [해석] 준비 중(더미 캠의 분석 캐시 생성 등). 이때 progress 0~100이 의미를 가진다.
    PREPARING,
    // [해석] 가동 중. 캠 이상 감지(FNC-A-035)는 이 상태의 캠만 검사한다.
    RUNNING,
    // [해석] 오류(영상 없음, 스트림 연결 실패 등). 내용은 Camera.errorMessage에.
    ERROR
}
