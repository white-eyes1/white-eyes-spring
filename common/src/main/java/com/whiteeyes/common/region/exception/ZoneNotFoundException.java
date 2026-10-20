package com.whiteeyes.common.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// 공통 화면(현재 현황 요약 등)용. 관리자 앱의 구역 관리는 admin region의 ZoneNotFoundException을 쓴다.
// TODO(#8): 지역·구역 목록(FNC-C-008)에서 공통 구역 조회가 생기면 그쪽 예외와 합친다
public class ZoneNotFoundException extends BusinessException {

    public ZoneNotFoundException() {
        super(ErrorCode.ZONE_NOT_FOUND, "구역을 찾을 수 없습니다.");
    }
}
