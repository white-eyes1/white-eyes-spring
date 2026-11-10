package com.whiteeyes.admin.camera.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 캠 관리 화면 SCR-A-006과 그 위에 뜨는 캠 등록 창 SCR-A-007(FNC-A-018·020·021·022), 관리자 앱 주소 /admin/cameras.
 * 표와 창의 데이터는 화면 JS가 /api/v1/manage/cameras, /api/v1/zones로 받아 그린다.
 */
@Controller
public class CameraPageController {

    @GetMapping("/cameras")
    public String cameraPage() {
        return "admin/cameras";
    }
}
