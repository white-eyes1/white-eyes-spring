/*
 * [해석본] 원본: admin/src/test/java/com/whiteeyes/admin/media/service/RecordingPathValidatorTest.java
 * 관련 이슈: #17(FNC-A-026 "경로 규칙 위반: 400")
 *
 * [무엇] 경로 규칙(결정 3)을 항목마다 하나씩 확인한다. 각 테스트는 '규칙 하나만 어기고 나머지는 정상'인 경로를 써서,
 *   실패하면 어느 규칙이 깨졌는지 바로 알 수 있게 했다.
 * [Spring 없이] 검사기는 순수 자바 객체라 new로 만든다. 빠르고, 원인이 이 클래스 하나로 좁혀진다.
 */
package com.whiteeyes.admin.media.service;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.common.camera.entity.CameraSourceType;

class RecordingPathValidatorTest {

    // [해석] 규칙에 맞는 기준 경로. 라이브는 conventions.md의 파일 이름 규칙(cam{id}_{yyyyMMdd_HHmm}) 그대로다.
    private static final String LIVE_VIDEO = "recordings/live/cam7/20261012/cam7_20261012_1403.mp4";
    private static final String LIVE_BOX = "recordings/live/cam7/20261012/cam7_20261012_1403.json.gz";
    private static final String DUMMY_VIDEO = "dummy/videos/a.mp4";
    private static final String DUMMY_BOX = "dummy/boxes/a.json.gz";

    private final RecordingPathValidator validator = new RecordingPathValidator();

    // [해석] 먼저 '정상은 통과'를 확인한다. 이게 없으면 무엇이든 거절하는 잘못된 검사기도 아래 거절 테스트를 모두 통과해 버린다.
    @Test
    @DisplayName("FNC-A-026 라이브·더미 캠의 규칙에 맞는 경로는 통과")
    void acceptsValidPaths() {
        assertThatNoException().isThrownBy(() -> validator.validate(CameraSourceType.LIVE, LIVE_VIDEO, LIVE_BOX));
        assertThatNoException().isThrownBy(() -> validator.validate(CameraSourceType.DUMMY, DUMMY_VIDEO, DUMMY_BOX));
    }

    // [해석] @ParameterizedTest + @ValueSource: 같은 테스트를 값 세 개로 세 번 돌린다(결과에 3건으로 나옴).
    //        '공유 폴더 기준 상대경로가 아님'이라는 한 가지 위반의 세 모양(절대경로, 상위 폴더 이동, 역슬래시)이라 하나로 묶었다.
    //        Java 문자열 "\\"는 역슬래시 한 글자다.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/srv/media/recordings/live/cam7/a.mp4",
                "recordings/live/../../etc/a.mp4",
                "recordings\\live\\cam7\\a.mp4"
            })
    @DisplayName("FNC-A-026 절대경로·상위 폴더 이동·역슬래시가 있으면 400")
    void rejectsPathOutsideSharedFolder(String videoPath) {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.LIVE, videoPath, LIVE_BOX))
                .isInstanceOf(RecordingRegisterException.class);
    }

    // [해석] 영상의 확장자만 틀림(.avi). 폴더는 dummy/ 아래로 맞춰 확장자 규칙만 걸리게 했다.
    @Test
    @DisplayName("FNC-A-026 영상이 .mp4가 아니면 400")
    void rejectsVideoWithWrongExtension() {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.DUMMY, "dummy/videos/a.avi", DUMMY_BOX))
                .isInstanceOf(RecordingRegisterException.class);
    }

    // [해석] 좌표 파일이 .json(압축 안 됨). 다시보기 화면은 gzip을 기대하므로(conventions.md) .json.gz만 받는다.
    @Test
    @DisplayName("FNC-A-026 좌표 파일이 .json.gz가 아니면 400")
    void rejectsBoxWithWrongExtension() {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.DUMMY, DUMMY_VIDEO, "dummy/boxes/a.json"))
                .isInstanceOf(RecordingRegisterException.class);
    }

    // [해석] 아래 두 테스트는 입력 방식별 시작 폴더(해석으로 더한 규칙). 서로 상대 폴더의 정상 경로를 넣어,
    //        '경로 자체는 멀쩡하지만 캠 종류와 폴더가 안 맞는' 경우만 걸리게 했다.
    @Test
    @DisplayName("FNC-A-026 라이브 캠인데 recordings/live/ 아래가 아니면 400")
    void rejectsLivePathOutsideLiveFolder() {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.LIVE, DUMMY_VIDEO, DUMMY_BOX))
                .isInstanceOf(RecordingRegisterException.class);
    }

    @Test
    @DisplayName("FNC-A-026 더미 캠인데 dummy/ 아래가 아니면 400")
    void rejectsDummyPathOutsideDummyFolder() {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.DUMMY, LIVE_VIDEO, LIVE_BOX))
                .isInstanceOf(RecordingRegisterException.class);
    }
}
