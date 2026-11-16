package com.whiteeyes.admin.media.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 다시보기 화면 SCR-A-009(FNC-A-047), 관리자 앱 주소 /admin/playback?zoneId=&at=.
 * 녹화 찾기(FNC-A-043)와 통계는 화면 JS가 API로 받는다.
 */
@Controller
public class PlaybackPageController {

    // 화면 틀(템플릿)만 돌려준다. 녹화·통계 데이터는 화면 JS가 API로 받아 채운다.
    @GetMapping("/playback")
    public String playbackPage() {
        return "admin/playback";
    }
}
