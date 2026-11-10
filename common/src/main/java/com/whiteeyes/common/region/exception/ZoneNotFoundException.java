package com.whiteeyes.common.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 공통 화면(현재 현황 요약 등)용. 관리자 앱의 구역 관리는 admin region의 ZoneNotFoundException을 쓴다.
// 두 예외는 같은 ErrorCode.ZONE_NOT_FOUND(404)를 쓰고, 메시지만 다르다(admin은 zoneId를 붙임). 합칠 계획은 없다.
public class ZoneNotFoundException extends BusinessException {

    public ZoneNotFoundException() {
        super(ErrorCode.ZONE_NOT_FOUND, "구역을 찾을 수 없습니다.");
    }
}
