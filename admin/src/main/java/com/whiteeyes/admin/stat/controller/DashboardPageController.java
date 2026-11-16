package com.whiteeyes.admin.stat.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 통계 메인(대시보드) 화면 SCR-C-003(FNC-C-008·009·016, 캠 패널 FNC-A-022), 관리자 앱 주소 /admin/stats.
 * 화면은 두 앱이 함께 쓰는 common 템플릿(stats/main) 하나이고, admin 값으로 캠 패널·지역 등록 버튼을 켠다.
 * 지역(regionId)은 주소 쿼리를 화면 JS가 읽는다.
 */
@Controller
public class DashboardPageController {

    // 캠 카드 미리보기 갱신 간격(초, conventions.md CARD_SNAPSHOT_INTERVAL_SEC). 화면 JS가 data 속성으로 받는다
    private final long cardSnapshotIntervalSec;

    /** @param cardSnapshotIntervalSec 환경변수 CARD_SNAPSHOT_INTERVAL_SEC. 없으면 2초 */
    public DashboardPageController(@Value("${CARD_SNAPSHOT_INTERVAL_SEC:2}") long cardSnapshotIntervalSec) {
        this.cardSnapshotIntervalSec = cardSnapshotIntervalSec;
    }

    /** 통계 메인 화면을 연다. 지도·캠 카드 데이터는 화면 JS가 API로 불러온다. */
    @GetMapping("/stats")
    public String dashboardPage(Model model) {
        // common 템플릿을 관리자 앱이 함께 쓰므로, 관리자 전용 부분(캠 패널·지역 등록 버튼)을 켜는 표시를 넘긴다
        model.addAttribute("admin", true);
        // 화면 JS가 data 속성으로 읽어 캠 카드 미리보기를 이 간격(초)마다 새로 가져온다
        model.addAttribute("cardSnapshotIntervalSec", cardSnapshotIntervalSec);
        // 뷰 이름. 두 앱이 함께 쓰는 common 템플릿이다
        return "stats/main";
    }
}
