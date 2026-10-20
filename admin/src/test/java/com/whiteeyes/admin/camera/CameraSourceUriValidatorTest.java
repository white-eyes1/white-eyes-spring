package com.whiteeyes.admin.camera;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.admin.camera.exception.InvalidSourceUriException;
import com.whiteeyes.admin.camera.service.CameraSourceUriValidator;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CameraSourceUriValidatorTest {

    private final CameraSourceUriValidator validator = new CameraSourceUriValidator();

    @ParameterizedTest
    @ValueSource(strings = {"rtsp://192.168.0.10:554/stream1", "rtsp://user:pw@cam.example.com/live", "rtsp://a"})
    @DisplayName("LIVE는 rtsp:// 로 시작하면 통과")
    void live_valid(String uri) {
        assertThatCode(() -> validator.validate(CameraSourceType.LIVE, uri)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.168.0.10/stream", "RTSP://192.168.0.10/stream", "rtsp:/192.168.0.10", "dummy/videos/a.mp4"})
    @DisplayName("LIVE는 rtsp:// 로 시작하지 않으면 거부")
    void live_invalid(String uri) {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.LIVE, uri))
                .isInstanceOf(InvalidSourceUriException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dummy/videos/a.mp4", "dummy/videos/sub/b.mp4", "dummy/videos/영상 1.mp4"})
    @DisplayName("DUMMY는 dummy/videos/ 로 시작하고 .mp4 로 끝나면 통과")
    void dummy_valid(String uri) {
        assertThatCode(() -> validator.validate(CameraSourceType.DUMMY, uri)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "videos/a.mp4",
        "/srv/media/dummy/videos/a.mp4",
        "/dummy/videos/a.mp4",
        "dummy/videos/a.avi",
        "dummy/videos/a.mp4.txt",
        "dummy/videos/../a.mp4",
        "dummy/videos/..a.mp4",
        "rtsp://192.168.0.10/stream"
    })
    @DisplayName("DUMMY는 상대경로 규칙을 어기면 거부")
    void dummy_invalid(String uri) {
        assertThatThrownBy(() -> validator.validate(CameraSourceType.DUMMY, uri))
                .isInstanceOf(InvalidSourceUriException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {255})
    @DisplayName("255자까지는 통과하고 256자부터 거부")
    void length_limit(int maxLength) {
        String within = "rtsp://" + "a".repeat(maxLength - "rtsp://".length());
        String over = within + "a";

        assertThatCode(() -> validator.validate(CameraSourceType.LIVE, within)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(CameraSourceType.LIVE, over))
                .isInstanceOf(InvalidSourceUriException.class);
    }
}
