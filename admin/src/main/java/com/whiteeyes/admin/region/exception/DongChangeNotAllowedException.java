package com.whiteeyes.admin.region.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 수정한 다각형의 소속 동이 원래 동과 다를 때(400 DONG_CHANGE_NOT_ALLOWED, FNC-A-015). */
public class DongChangeNotAllowedException extends BusinessException {

    public DongChangeNotAllowedException(String dongName) {
        super(ErrorCode.DONG_CHANGE_NOT_ALLOWED, "수정한 모양이 '" + dongName + "'에 속해 같은 동 안에서만 바꿀 수 있습니다.");
    }
}
