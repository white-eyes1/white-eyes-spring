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

    private static final String LIVE_VIDEO = "recordings/live/cam7/20261012/cam7_20261012_1403.mp4";
    private static final String LIVE_BOX = "recordings/live/cam7/20261012/cam7_20261012_1403.json.gz";
    private static final String DUMMY_VIDEO = "dummy/videos/a.mp4";
    private static final String DUMMY_BOX = "dummy/boxes/a.json.gz";

    private final RecordingPathValidator validator = new RecordingPathValidator();

    @Test
    @DisplayName("FNC-A-026 라이브·더미 캠의 규칙에 맞는 경로는 통과")
    void acceptsValidPaths() {
        assertThatNoException().isThrownBy(() -> validator.validate(CameraSourceType.LIVE, LIVE_VIDEO, LIVE_BOX));
        assertThatNoException().isThrownBy(() -> validator.validate(CameraSourceType.DUMMY, DUMMY_VIDEO, DUMMY_BOX));
    }

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

    @Test
    @DisplayName("FNC-A-026 영상이 .mp4가 아니면 400")
    void rejectsVideoWithWrongExtension() {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.DUMMY, "dummy/videos/a.avi", DUMMY_BOX))
                .isInstanceOf(RecordingRegisterException.class);
    }

    @Test
    @DisplayName("FNC-A-026 좌표 파일이 .json.gz가 아니면 400")
    void rejectsBoxWithWrongExtension() {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.DUMMY, DUMMY_VIDEO, "dummy/boxes/a.json"))
                .isInstanceOf(RecordingRegisterException.class);
    }

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
