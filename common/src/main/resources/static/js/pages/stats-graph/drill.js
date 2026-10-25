// 단계별 들어가기(FNC-C-013): 그래프 점의 시각과 지금 단위로 한 단계 아래 단위·기간을 정한다.
// 1y → 그 해 1M, 1M → 그 달 1d, 1w → 그 주 1d, 1d → 그 날 1h, 1h → 그 시간 1m, 1m → 더 내려가지 않음.
// 시각은 브라우저 시각으로 다룬다(stats-graph.js와 같음). to는 끝을 포함하지 않는 다음 구간 시작이다.

const HOUR_MS = 3_600_000;

/**
 * 한 단계 아래 그래프 조건. 1m 점이면 null(사용자 앱은 동작 없음, 관리자 앱은 다시보기).
 * @param {string} interval 지금 단위(1m·1h·1d·1w·1M·1y)
 * @param {Date} at 누른 점의 구간 시작 시각
 * @returns {{interval: string, from: Date, to: Date} | null}
 */
export function drillDown(interval, at) {
    const year = at.getFullYear();
    const month = at.getMonth();
    const day = at.getDate();
    switch (interval) {
        case '1y':
            return { interval: '1M', from: new Date(year, 0, 1), to: new Date(year + 1, 0, 1) };
        case '1M':
            return { interval: '1d', from: new Date(year, month, 1), to: new Date(year, month + 1, 1) };
        case '1w':
            // 1w 점은 월요일 시작(conventions.md '통계 단위')이라 그 날부터 7일
            return { interval: '1d', from: new Date(year, month, day), to: new Date(year, month, day + 7) };
        case '1d':
            return { interval: '1h', from: new Date(year, month, day), to: new Date(year, month, day + 1) };
        case '1h': {
            const from = new Date(year, month, day, at.getHours());
            return { interval: '1m', from, to: new Date(from.getTime() + HOUR_MS) };
        }
        default:
            return null;
    }
}

/**
 * 이동 경로(연>월>일>시)에 붙일 이름. 바로 위 칸에 이미 있는 연·월은 빼고 쓴다.
 * @param {string} interval 누른 점의 단위
 * @param {Date} at 누른 점의 구간 시작 시각
 * @param {string|null} parentInterval 바로 위 칸을 만든 점의 단위(처음 칸이면 null)
 */
export function crumbLabel(interval, at, parentInterval) {
    const year = at.getFullYear();
    const month = at.getMonth() + 1;
    const day = at.getDate();
    switch (interval) {
        case '1y':
            return `${year}년`;
        case '1M':
            return parentInterval === '1y' ? `${month}월` : `${year}년 ${month}월`;
        case '1w':
            return `${year}년 ${month}월 ${day}일 주`;
        case '1d':
            return parentInterval === '1M' ? `${day}일` : parentInterval === '1w' ? `${month}월 ${day}일` : `${year}년 ${month}월 ${day}일`;
        case '1h':
            return `${at.getHours()}시`;
        default:
            return '';
    }
}
