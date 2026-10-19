/*
 * [해석본] 원본: admin/src/main/java/com/whiteeyes/admin/media/service/RecordingPathValidator.java
 * 관련 이슈: #17(FNC-A-026 업무 규칙 "경로는 /srv/media 기준 상대경로", 예외 "경로 규칙 위반: 400")
 *
 * [무엇] 분석 서버가 보낸 영상·좌표 파일 경로가 공유 폴더 규칙에 맞는지 확인한다.
 * [왜 중요한가] 이 경로는 나중에 다시보기에서 웹 서버가 파일을 보낼 때 그대로 쓰인다(overview.md '다시보기').
 *   공유 폴더 밖을 가리키는 경로가 DB에 들어가면, 다시보기 때 엉뚱한 파일을 보내려 하거나 찾지 못한다.
 *   그래서 등록하는 입구에서 막는다.
 * [어떤 규칙을 넣었나] (사용자와 합의한 결정 3)
 *   명세에 적힌 것: 공유 폴더 기준 상대경로 → 절대경로·상위 폴더 이동 금지, 파일 종류(.mp4, .json.gz)
 *   해석으로 더한 것(PR에 '검토 필요'): 라이브·더미별 시작 폴더, 역슬래시 금지
 *   길이(255자)는 DTO의 @Size가 먼저 검사하므로 여기서는 다시 보지 않는다.
 * [왜 별도 클래스인가] 서비스의 흐름(캠 확인 → 규칙 확인 → 저장)과 '경로 문자열 검사'라는 세부 규칙을 나눠
 *   서비스가 한 가지 일에 집중하게 했다(code-conventions 2-5). 따로 떼어 두면 경로 규칙만 단독으로 테스트하기도 쉽다.
 */
package com.whiteeyes.admin.media.service;

import org.springframework.stereotype.Component;

import com.whiteeyes.admin.media.exception.RecordingRegisterException;
import com.whiteeyes.common.camera.entity.CameraSourceType;

/**
 * 녹화 파일 경로가 공유 폴더(/srv/media) 기준 상대경로 규칙을 지키는지 확인한다(FNC-A-026, conventions.md '공유 폴더 구조').
 */
// [해석] @Component: 상태가 없는 검사기라 Spring이 하나만 만들어 서비스에 넣어 준다. 테스트에서는 new로 바로 만든다.
@Component
public class RecordingPathValidator {

    // [해석] conventions.md '공유 폴더 구조'의 폴더·확장자를 상수로 뺐다(매직 문자열 금지, code-conventions 2-5).
    //        라이브: recordings/live/cam{id}/{yyyyMMdd}/cam{id}_{yyyyMMdd_HHmm}.mp4 / .json.gz
    //        더미: 영상 dummy/videos/…mp4, 좌표 dummy/boxes/…json.gz → 둘 다 'dummy/' 아래라 공통 접두사로 본다.
    private static final String LIVE_PATH_PREFIX = "recordings/live/";
    private static final String DUMMY_PATH_PREFIX = "dummy/";
    private static final String VIDEO_EXTENSION = ".mp4";
    private static final String BOX_EXTENSION = ".json.gz";

    // [해석] 영상과 좌표 파일에 같은 규칙을 적용하되 확장자만 다르게 넘긴다. 같은 검사를 두 번 쓰지 않으려고 아래 메서드로 묶었다.
    public void validate(CameraSourceType sourceType, String videoPath, String boxPath) {
        validatePath("videoPath", videoPath, sourceType, VIDEO_EXTENSION);
        validatePath("boxPath", boxPath, sourceType, BOX_EXTENSION);
    }

    // [해석] field(어느 칸인지)를 함께 받아 오류 메시지에 넣는다. 분석 서버 로그에서 어느 경로가 틀렸는지 바로 보이게.
    private void validatePath(String field, String path, CameraSourceType sourceType, String extension) {
        // 공유 폴더 밖을 가리키지 못하게 절대경로·상위 폴더 이동을 막고, 구분자는 '/' 하나로 맞춘다
        // [해석] 세 조건을 하나로 묶은 이유: 모두 '공유 폴더 기준 상대경로가 아님'이라는 같은 위반이다.
        //        "/"로 시작 → 절대경로. ".." → 상위 폴더로 올라가 공유 폴더 밖을 가리킬 수 있음(conventions.md 캠 영상 주소 규칙과 같은 기준).
        //        "\\"(역슬래시) → 경로 구분자를 '/' 하나로 통일. 두 가지가 섞이면 같은 파일이 다른 문자열로 저장될 수 있다.
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new RecordingRegisterException(field + "는 공유 폴더 기준 상대경로여야 합니다.");
        }
        // [해석] 파일 종류 확인. 다시보기 화면은 영상을 mp4로, 좌표를 gzip JSON으로 받는다고 정해져 있다(conventions.md nginx 규칙).
        if (!path.endsWith(extension)) {
            throw new RecordingRegisterException(field + "는 " + extension + " 파일이어야 합니다.");
        }
        // [해석] 입력 방식별 시작 폴더 확인(해석으로 더한 규칙). 라이브 녹화가 더미 폴더를 가리키거나 그 반대인 경우를 막는다.
        //        캠의 입력 방식(sourceType)은 요청이 아니라 DB의 캠 정보에서 온 값이라, 분석 서버가 경로를 잘못 만든 경우를 잡아낸다.
        String prefix = sourceType == CameraSourceType.LIVE ? LIVE_PATH_PREFIX : DUMMY_PATH_PREFIX;
        if (!path.startsWith(prefix)) {
            throw new RecordingRegisterException(field + "는 " + prefix + " 아래 경로여야 합니다.");
        }
    }
}
