/*
 * [해석본] 원본: common/src/main/java/com/whiteeyes/common/camera/entity/CameraSourceType.java
 * 관련 이슈: #17(FNC-A-026) — TODO(#11) 임시 코드(#15·#16·#17 브랜치에서 같은 내용)
 *
 * [무엇] 캠의 입력 방식 2가지. tables.md camera.source_type "입력 방식: LIVE / DUMMY"와 같은 철자.
 * [왜 enum인가] CameraStatus와 같은 이유. 글자 오타를 컴파일러가 잡고, Camera가 EnumType.STRING으로 저장하므로
 *   이 이름이 곧 DB에 들어가는 값이다.
 * [#17에서 쓰는 곳] 녹화 등록 규칙이 입력 방식마다 다르다.
 *   - LIVE: 1분 파일마다 등록, endedAt 필수, 경로는 recordings/live/ 아래
 *   - DUMMY: 영상이 처음부터 다시 시작될 때마다 등록, endedAt 없음, 경로는 dummy/ 아래, 이전 반복을 닫음
 */
package com.whiteeyes.common.camera.entity;

// TODO(#11): 캠 등록(FNC-A-018)에서 정식으로 만든다. FNC-A-026 구현을 위해 먼저 둔 임시 코드
public enum CameraSourceType {
    // [해석] 실시간 RTSP 스트림을 분석하는 캠(FNC-S-007·008)
    LIVE,
    // [해석] 공유 폴더에 미리 올린 영상을 반복 재생하는 캠(FNC-S-009·010)
    DUMMY
}
