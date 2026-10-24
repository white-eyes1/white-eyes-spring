package com.whiteeyes.common.stat.exception;

import java.text.NumberFormat;
import java.util.Locale;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 엑셀 한 시트의 예상 행 수가 최대를 넘은 요청(FNC-C-023 처리 순서 1, 400 TOO_MANY_ROWS). */
public class TooManyRowsException extends BusinessException {

    public TooManyRowsException(long expectedRows, long maxRows) {
        super(
                ErrorCode.TOO_MANY_ROWS,
                "한 번에 최대 " + format(maxRows) + "행까지 내려받을 수 있습니다(예상 " + format(expectedRows)
                        + "행). 기간을 줄이거나 단위를 키워 주세요.");
    }

    private static String format(long number) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(number);
    }
}
