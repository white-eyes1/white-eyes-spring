package com.whiteeyes.common.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

// TODO(#8): 지역·구역 목록(FNC-C-008)에서 ErrorCode에 REGION_NOT_FOUND가 생기면 그 코드로 바꾼다.
// 지금은 ErrorCode를 고치지 않으려고 공통 NOT_FOUND를 쓴다(#8과 같은 줄 충돌 방지)
public class RegionNotFoundException extends BusinessException {

    public RegionNotFoundException() {
        super(ErrorCode.NOT_FOUND, "지역을 찾을 수 없습니다.");
    }
}
