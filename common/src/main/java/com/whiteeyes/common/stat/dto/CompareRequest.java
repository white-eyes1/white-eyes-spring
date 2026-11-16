package com.whiteeyes.common.stat.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.whiteeyes.common.stat.exception.InvalidRangeException;

/**
 * 기간 비교 조건(FNC-C-020 입력 항목). 기간은 날짜(yyyy-MM-dd)로 받고 양끝 날짜를 포함한다.
 * 기준 기간은 baseFrom·baseTo 또는 basePreset 중 하나로 정한다.
 */
public record CompareRequest(
        DashboardTargetType targetType, long targetId, ComparePeriod target, ComparePeriod base, int thresholdPct) {

    // 비정상 기준 %를 안 보냈을 때 쓰는 기본값
    public static final int DEFAULT_THRESHOLD_PCT = 30;
    // 화면의 입력 범위(screens.md SCR-C-005 '비정상 기준 %')
    public static final int MIN_THRESHOLD_PCT = 5;
    public static final int MAX_THRESHOLD_PCT = 100;

    // 검증 오류(BindException)에 붙일 객체 이름. 오류 응답의 칸별 오류가 이 이름 아래로 묶인다
    private static final String OBJECT_NAME = "compareRequest";
    // GlobalExceptionHandler의 형식 오류·필수 문구와 같은 문장
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다.";
    private static final String REQUIRED_MESSAGE = "필수입니다.";
    private static final String BASE_REQUIRED_MESSAGE = "기준 기간을 지정해 주세요.";
    private static final String BASE_CONFLICT_MESSAGE = "기준 기간은 프리셋과 날짜 중 하나만 지정해 주세요.";
    private static final String THRESHOLD_RANGE_MESSAGE =
            "비정상 기준은 " + MIN_THRESHOLD_PCT + "~" + MAX_THRESHOLD_PCT + " 사이로 입력해 주세요.";
    private static final String REVERSED_MESSAGE = "끝 날짜는 시작 날짜보다 앞설 수 없습니다.";
    private static final String TOO_LONG_MESSAGE = "각 기간은 최대 1년까지 지정할 수 있습니다.";

    /**
     * 요청 값을 읽고 검사한다.
     *
     * @param thresholdPct 없으면 기본 30
     * @throws BindException 날짜·프리셋 형식, 기준 기간 지정 방식, 기준 % 범위가 틀리면(400 INVALID_INPUT, 칸별 오류)
     * @throws InvalidRangeException 끝 날짜가 시작보다 앞서거나 기간이 1년을 넘으면(400 INVALID_RANGE)
     */
    public static CompareRequest parse(
            DashboardTargetType targetType,
            long targetId,
            String fromText,
            String toText,
            String baseFromText,
            String baseToText,
            String basePresetText,
            Integer thresholdPct)
            throws BindException {
        // 오류를 바로 던지지 않고 이 객체에 모아 둔다. 여러 칸의 오류를 한 번에 돌려주려는 것이다
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), OBJECT_NAME);
        // 대상 기간의 시작·끝 날짜를 읽는다. 형식이 틀리면 errors에 쌓고 빈 값으로 돌아온다
        Optional<LocalDate> from = parseDate(errors, "from", fromText);
        Optional<LocalDate> to = parseDate(errors, "to", toText);

        // 기준 기간은 "프리셋" 또는 "직접 입력한 두 날짜" 중 하나만 허용한다. 아래 분기에서 어느 쪽인지 정한다
        Optional<BasePreset> preset = Optional.empty();
        Optional<LocalDate> baseFrom = Optional.empty();
        Optional<LocalDate> baseTo = Optional.empty();
        // 프리셋과 날짜를 동시에 보냈는지 구분하려고 먼저 각각 값이 있는지 계산한다
        boolean hasPreset = hasText(basePresetText);
        boolean hasBaseDates = hasText(baseFromText) || hasText(baseToText);
        // 둘 다 보냄: 어느 쪽을 쓸지 알 수 없으므로 오류. 둘 다 없음은 맨 아래 else에서 '필수' 오류로 처리한다
        if (hasPreset && hasBaseDates) {
            reject(errors, "basePreset", BASE_CONFLICT_MESSAGE);
        } else if (hasPreset) {
            preset = parsePreset(errors, basePresetText);
        } else if (hasBaseDates) {
            baseFrom = parseDate(errors, "baseFrom", baseFromText);
            baseTo = parseDate(errors, "baseTo", baseToText);
        } else {
            reject(errors, "basePreset", BASE_REQUIRED_MESSAGE);
        }

        // 비정상 기준 %: 없으면 기본 30, 있으면 화면 입력 범위(5~100) 안인지 확인한다
        int threshold = thresholdPct == null ? DEFAULT_THRESHOLD_PCT : thresholdPct;
        if (threshold < MIN_THRESHOLD_PCT || threshold > MAX_THRESHOLD_PCT) {
            reject(errors, "thresholdPct", THRESHOLD_RANGE_MESSAGE);
        }
        // 형식·조합 오류가 하나라도 있으면 여기서 한 번에 던진다. 아래 단계는 from/to 등이 모두 유효하다는 전제로 get()을 쓴다
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }

        // 대상 기간의 순서·최대 길이(1년)를 검사한다. 위반하면 INVALID_RANGE 예외
        ComparePeriod target = checkedPeriod(from.get(), to.get());
        // 프리셋이면 대상 기간을 7의 배수 일만큼 앞으로 옮겨 기준 기간을 만들고, 날짜를 직접 줬다면 그 기간을 같은 검사로 확인한다
        ComparePeriod base = preset.isPresent()
                ? target.minusDays(preset.get().getShiftDays())
                : checkedPeriod(baseFrom.get(), baseTo.get());
        return new CompareRequest(targetType, targetId, target, base, threshold);
    }

    // 1년 = 시작 날짜부터 1년 뒤 같은 날짜 전날까지(예: 2026-01-01 ~ 2026-12-31)
    /** 기간의 끝이 시작보다 앞서지 않는지, 1년을 넘지 않는지 검사하고 기간을 만든다. 위반하면 InvalidRangeException */
    private static ComparePeriod checkedPeriod(LocalDate from, LocalDate to) {
        // 끝 날짜가 시작 날짜보다 앞서면 의미 없는 기간이다
        if (to.isBefore(from)) {
            throw new InvalidRangeException(REVERSED_MESSAGE);
        }
        // 양끝을 포함하므로 "시작 + 1년 - 1일"이 허용되는 마지막 날이다(365/366일 모두 1년으로 취급)
        if (to.isAfter(from.plusYears(1).minusDays(1))) {
            throw new InvalidRangeException(TOO_LONG_MESSAGE);
        }
        return new ComparePeriod(from, to);
    }

    /** 날짜 문자열을 읽는다. 비었으면 '필수', 형식이 틀리면 '형식 오류'를 errors에 쌓고 빈 값을 돌려준다 */
    private static Optional<LocalDate> parseDate(MapBindingResult errors, String field, String text) {
        if (!hasText(text)) {
            reject(errors, field, REQUIRED_MESSAGE);
            return Optional.empty();
        }
        try {
            // ISO 날짜(yyyy-MM-dd)만 받는다
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            reject(errors, field, INVALID_FORMAT_MESSAGE);
            return Optional.empty();
        }
    }

    /** 프리셋 이름(LAST_MONTH 등)을 enum으로 바꾼다. 모르는 이름이면 형식 오류를 쌓는다 */
    private static Optional<BasePreset> parsePreset(MapBindingResult errors, String text) {
        try {
            return Optional.of(BasePreset.valueOf(text));
        } catch (IllegalArgumentException e) {
            reject(errors, "basePreset", INVALID_FORMAT_MESSAGE);
            return Optional.empty();
        }
    }

    /** null이거나 공백뿐이면 "값 없음"으로 본다 */
    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /** 칸(field) 하나에 대한 오류를 errors에 추가한다 */
    private static void reject(MapBindingResult errors, String field, String message) {
        errors.addError(new FieldError(OBJECT_NAME, field, message));
    }
}
