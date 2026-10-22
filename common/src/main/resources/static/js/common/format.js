// 화면에 보이는 날짜·숫자 형식을 한 곳에서 정한다(conventions.md '시각 형식', code-conventions 5-3).

const pad = (value) => String(value).padStart(2, '0');

function isBlank(value) {
    return value === null || value === undefined || value === '' || Number.isNaN(Number(value));
}

/** ISO 문자열·Date·밀리초를 'yyyy-MM-dd HH:mm'(브라우저 시간대)로. 값이 없거나 날짜가 아니면 '-'. */
export function formatDateTime(value) {
    if (value === null || value === undefined || value === '') {
        return '-';
    }
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '-';
    }
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
        `${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}

/** 숫자를 천 단위 쉼표로. 값이 없으면 '-'. */
export function formatNumber(value) {
    return isBlank(value) ? '-' : Number(value).toLocaleString('ko-KR');
}

/** 소수 자릿수를 고정해서(기본 1자리) 쉼표와 함께. 값이 없으면 '-'. */
export function formatDecimal(value, fractionDigits = 1) {
    if (isBlank(value)) {
        return '-';
    }
    return Number(value).toLocaleString('ko-KR', {
        minimumFractionDigits: fractionDigits,
        maximumFractionDigits: fractionDigits,
    });
}
