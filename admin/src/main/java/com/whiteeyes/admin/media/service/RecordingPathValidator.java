package com.whiteeyes.admin.media.service;

import org.springframework.stereotype.Component;

import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.common.camera.entity.CameraSourceType;

/**
 * 녹화 파일 경로가 공유 폴더(/srv/media) 기준 상대경로 규칙을 지키는지 확인한다(FNC-A-026, conventions.md '공유 폴더 구조').
 */
@Component
public class RecordingPathValidator {

    // 입력 방식별로 공유 폴더에서 파일이 있어야 하는 위치(앞부분). 라이브는 recordings/live/, 더미는 dummy/ 아래다.
    private static final String LIVE_PATH_PREFIX = "recordings/live/";
    private static final String DUMMY_PATH_PREFIX = "dummy/";
    private static final String VIDEO_EXTENSION = ".mp4";
    private static final String BOX_EXTENSION = ".json.gz";

    /**
     * 영상·좌표 파일 경로가 모두 규칙에 맞는지 확인한다. 어기면 RecordingRegisterException(400)을 던진다.
     *
     * @param sourceType 캠의 입력 방식(경로 위치 규칙을 가른다)
     */
    public void validate(CameraSourceType sourceType, String videoPath, String boxPath) {
        validatePath("videoPath", videoPath, sourceType, VIDEO_EXTENSION);
        validatePath("boxPath", boxPath, sourceType, BOX_EXTENSION);
    }

    /** 경로 하나를 검사한다. 순서: 공유 폴더 밖을 가리키지 않는지, 확장자가 맞는지, 입력 방식에 맞는 폴더 아래인지. field는 오류 메시지에 쓴다. */
    private void validatePath(String field, String path, CameraSourceType sourceType, String extension) {
        // 공유 폴더 밖을 가리키지 못하게 절대경로·상위 폴더 이동을 막고, 구분자는 '/' 하나로 맞춘다
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new RecordingRegisterException(field + "는 공유 폴더 기준 상대경로여야 합니다.");
        }
        if (!path.endsWith(extension)) {
            throw new RecordingRegisterException(field + "는 " + extension + " 파일이어야 합니다.");
        }
        // 입력 방식에 따라 허용 폴더가 다르다.
        String prefix = sourceType == CameraSourceType.LIVE ? LIVE_PATH_PREFIX : DUMMY_PATH_PREFIX;
        if (!path.startsWith(prefix)) {
            throw new RecordingRegisterException(field + "는 " + prefix + " 아래 경로여야 합니다.");
        }
    }
}
