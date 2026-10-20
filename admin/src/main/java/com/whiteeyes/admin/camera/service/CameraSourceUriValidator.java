package com.whiteeyes.admin.camera.service;

import com.whiteeyes.admin.camera.exception.InvalidSourceUriException;
import com.whiteeyes.common.camera.entity.CameraSourceType;
import org.springframework.stereotype.Component;

/**
 * 캠 영상 주소 규칙(conventions.md '입력값 규칙'). 파일·스트림이 실제로 있는지는 가동할 때 분석 서버가 확인한다.
 *
 * <p>LIVE: rtsp:// 로 시작, 255자 이하. DUMMY: 공유 폴더 기준 상대경로로 dummy/videos/ 로 시작하고 .mp4 로
 * 끝나며 '..'를 포함하지 않는다. 캠 정보 수정(FNC-A-019)도 이 규칙을 쓴다.
 */
@Component
public class CameraSourceUriValidator {

    private static final int MAX_LENGTH = 255;
    private static final String LIVE_PREFIX = "rtsp://";
    private static final String DUMMY_PREFIX = "dummy/videos/";
    private static final String DUMMY_SUFFIX = ".mp4";
    private static final String PARENT_PATH = "..";

    public void validate(CameraSourceType sourceType, String sourceUri) {
        if (sourceUri.length() > MAX_LENGTH) {
            throw new InvalidSourceUriException("영상 주소는 " + MAX_LENGTH + "자 이하로 입력해 주세요.");
        }
        switch (sourceType) {
            case LIVE -> {
                if (!sourceUri.startsWith(LIVE_PREFIX)) {
                    throw new InvalidSourceUriException("실시간(LIVE) 영상 주소는 " + LIVE_PREFIX + " 로 시작해야 합니다.");
                }
            }
            case DUMMY -> {
                if (!sourceUri.startsWith(DUMMY_PREFIX)
                        || !sourceUri.endsWith(DUMMY_SUFFIX)
                        || sourceUri.contains(PARENT_PATH)) {
                    throw new InvalidSourceUriException("더미(DUMMY) 영상 주소는 " + DUMMY_PREFIX + " 로 시작하고 "
                            + DUMMY_SUFFIX + " 로 끝나는 상대경로여야 하며 '..'를 쓸 수 없습니다.");
                }
            }
        }
    }
}
