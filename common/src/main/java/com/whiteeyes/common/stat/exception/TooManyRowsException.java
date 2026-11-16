package com.whiteeyes.common.stat.exception;

import java.text.NumberFormat;
import java.util.Locale;

import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;

/** 엑셀 한 시트의 예상 행 수가 최대를 넘은 요청(FNC-C-023 처리 순서 1, 400 TOO_MANY_ROWS). */
public class TooManyRowsException extends BusinessException {

    // 한도와 예상 행 수를 모두 알려 사용자가 기간·단위를 얼마나 줄여야 하는지 가늠하게 한다
    public TooManyRowsException(long expectedRows, long maxRows) {
        super(
                ErrorCode.TOO_MANY_ROWS,
                "한 번에 최대 " + format(maxRows) + "행까지 내려받을 수 있습니다(예상 " + format(expectedRows)
                        + "행). 기간을 줄이거나 단위를 키워 주세요.");
    }

    // 1,000,000처럼 천 단위 쉼표를 넣어 읽기 쉽게 만든다(한국 로케일)
    private static String format(long number) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(number);
    }
}
