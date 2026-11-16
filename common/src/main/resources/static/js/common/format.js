// 화면에 보이는 날짜·숫자 형식을 한 곳에서 정한다(conventions.md '시각 형식', code-conventions 5-3).

// 한 자리 숫자를 두 자리로 맞춘다(5 -> '05'). 날짜·시각 문자열 조립에 쓴다
const pad = (value) => String(value).padStart(2, '0');

// 값이 비었거나 숫자로 바꿀 수 없으면 true. 화면에는 '-'로 보여 준다
function isBlank(value) {
    return value === null || value === undefined || value === '' || Number.isNaN(Number(value));
}

/** ISO 문자열·Date·밀리초를 'yyyy-MM-dd HH:mm'(브라우저 시간대)로. 값이 없거나 날짜가 아니면 '-'. */
export function formatDateTime(value) {
    // 값이 비어 있으면 '-'
    if (value === null || value === undefined || value === '') {
        return '-';
    }
    // Date가 아니면 new Date로 해석한다(ISO 문자열·밀리초 모두 가능)
    const date = value instanceof Date ? value : new Date(value);
    // 해석 결과가 Invalid Date면 getTime()이 NaN이다
    if (Number.isNaN(date.getTime())) {
        return '-';
    }
    // 월은 0부터 시작하므로 +1 한다
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
        `${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}

/** 숫자를 천 단위 쉼표로. 값이 없으면 '-'. */
export function formatNumber(value) {
    // 한국 표기(1,234)로 천 단위 쉼표를 넣는다
    return isBlank(value) ? '-' : Number(value).toLocaleString('ko-KR');
}

/** 증감을 부호와 함께(+18.4, −35, 0). 감소는 빼기 기호(−)를 쓴다. 값이 없으면 '-'. */
export function formatSigned(value, fractionDigits = 1) {
    // 값이 없으면 '-'
    if (isBlank(value)) {
        return '-';
    }
    // 부호는 따로 붙이므로 절댓값을 문자열로 만든다
    const number = Number(value);
    // formatDecimal은 아래에 함수 선언으로 있어 호이스팅되어 먼저 써도 된다
    const text = formatDecimal(Math.abs(number), fractionDigits);
    // 예: 0.04를 소수 1자리로 반올림하면 0.0이 되므로 '+0.0' 대신 부호 없이 보인다(쉼표를 지우고 숫자로 비교)
    // 반올림해서 0이 되면 부호를 붙이지 않는다
    if (Number(text.replace(/,/g, '')) === 0) {
        return text;
    }
    // 양수는 '+', 음수는 일반 하이픈 대신 빼기 기호(−)를 쓴다
    return (number > 0 ? '+' : '−') + text;
}

/** 소수 자릿수를 고정해서(기본 1자리) 쉼표와 함께. 값이 없으면 '-'. */
export function formatDecimal(value, fractionDigits = 1) {
    // 값이 없으면 '-'
    if (isBlank(value)) {
        return '-';
    }
    // 최소·최대 자릿수를 같게 두어 소수 자릿수를 고정한다(3 -> '3.0')
    return Number(value).toLocaleString('ko-KR', {
        minimumFractionDigits: fractionDigits,
        maximumFractionDigits: fractionDigits,
    });
}
