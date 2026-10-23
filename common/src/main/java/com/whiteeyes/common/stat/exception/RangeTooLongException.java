package com.whiteeyes.common.stat.exception;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.stat.dto.StatInterval;

/** 단위별 최대 조회 기간을 넘은 요청(FNC-C-012 예외 상황, 400 RANGE_TOO_LONG). */
public class RangeTooLongException extends BusinessException {

    // 화면 문구 '최대 n까지 조회할 수 있습니다'(SCR-C-004 기간)와 같은 문장
    public RangeTooLongException(StatInterval interval) {
        super(
                ErrorCode.RANGE_TOO_LONG,
                interval.getLabel() + " 단위는 최대 " + interval.getMaxRangeLabel() + "까지 조회할 수 있습니다.");
    }
}
