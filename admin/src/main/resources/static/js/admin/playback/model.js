// 다시보기 화면(SCR-A-009)의 계산만 모아 둔 파일. 화면(DOM)을 건드리지 않아 따로 시험할 수 있다.
// 시각은 모두 밀리초(UTC 기준 epoch)로 다루고, 화면에 보일 때만 한국 시간(KST)으로 바꾼다.

// 시간 단위 상수(밀리초). KST_OFFSET은 한국 시간이 UTC보다 9시간 빠른 것을 나타낸다.
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const KST_OFFSET = 9 * HOUR;

/** 시간축에 보이는 범위(밀리초). '오늘'은 windowFor에서 그 날 0시~24시로 따로 계산한다. */
export const RANGE_MS = { '30m': 30 * MINUTE, '1h': HOUR, '3h': 3 * HOUR };

// 한 자리 숫자를 두 자리로 맞춘다(예: 3 -> '03').
const pad = (value) => String(value).padStart(2, '0');

// 브라우저 시간대와 무관하게 항상 한국 시간으로 표시하려고, 밀리초에 9시간을 더한 뒤 UTC getter로 읽는다.
function kstParts(ms) {
    const date = new Date(ms + KST_OFFSET);
    return {
        year: date.getUTCFullYear(),
        month: pad(date.getUTCMonth() + 1),
        day: pad(date.getUTCDate()),
        hour: pad(date.getUTCHours()),
        minute: pad(date.getUTCMinutes()),
        second: pad(date.getUTCSeconds()),
    };
}

/** 'yyyy-MM-dd HH:mm:ss'(한국 시간) */
export function formatClock(ms) {
    const p = kstParts(ms);
    return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/** 'HH:mm'(한국 시간) */
export function formatHm(ms) {
    const p = kstParts(ms);
    return `${p.hour}:${p.minute}`;
}

/** API에 보내는 시각: '2026-10-12T14:03:00+09:00' */
export function toKstIso(ms) {
    const p = kstParts(ms);
    return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}:${p.second}+09:00`;
}

/** 시각을 1분 단위로 내린 값. 1분 통계는 이 값을 키로 맞춘다. */
// 1분 통계는 분 시작 시각을 키로 삼으므로, 밀리초를 분 단위로 내림해 서로 비교할 수 있게 한다.
export function minuteStart(ms) {
    return Math.floor(ms / MINUTE) * MINUTE;
}

/**
 * 시간축 범위. 30분·1시간·3시간은 center를 가운데로, 'today'는 center가 속한 날 0시~24시(한국 시간).
 * @returns {{from: number, to: number}}
 */
export function windowFor(rangeKey, center) {
    // '오늘'은 한국 시간 기준 그 날 0시를 구한다. 한국 시간으로 옮겨 하루 단위로 내림한 뒤 다시 9시간을 빼 UTC 기준 밀리초로 돌린다.
    if (rangeKey === 'today') {
        const dayStart = Math.floor((center + KST_OFFSET) / DAY) * DAY - KST_OFFSET;
        return { from: dayStart, to: dayStart + DAY };
    }
    // 알 수 없는 범위 키는 1시간으로 대체한다.
    const span = RANGE_MS[rangeKey] ?? RANGE_MS['1h'];
    // 시작을 분 단위로 내려 1분 통계 칸의 경계와 맞춘다.
    const from = minuteStart(center - span / 2);
    return { from, to: from + span };
}

/** 범위 안 위치를 0~1로. 범위 밖이면 0 또는 1로 잘라 준다. */
export function fraction(ms, from, to) {
    // 범위 밖 값은 0~1로 잘라 화면 밖으로 삐져나가지 않게 한다.
    return Math.min(1, Math.max(0, (ms - from) / (to - from)));
}

/** 0~1 위치를 시각으로. */
export function timeAt(position, from, to) {
    return from + Math.min(1, Math.max(0, position)) * (to - from);
}

/** 녹화가 그 시각을 포함하는가. 종료 시각이 없으면 진행 중이라 끝이 없는 것으로 본다(FNC-A-043과 같은 규칙). */
export function recordingCovers(recording, ms) {
    if (!recording) {
        return false;
    }
    // 시작 시각은 포함, 종료 시각은 제외(반열린 구간)다. 서버의 녹화 조회 규칙(FNC-A-043)과 같아야 화면과 서버가 같은 녹화를 가리킨다.
    return recording.startedAt <= ms && (recording.endedAt === null || ms < recording.endedAt);
}

/** 그 1분 칸 [minuteMs, minuteMs+1분)에 겹치는 녹화 구간이 있는가. ranges는 {from, to}(ms)이며 null이면 알 수 없으므로 true. */
export function recordedInMinute(ranges, minuteMs) {
    // 녹화 구간 정보를 아직 못 받았으면 녹화가 있다고 보고 막지 않는다.
    if (ranges === null) {
        return true;
    }
    // 구간이 1분 칸과 조금이라도 겹치면 녹화된 것으로 본다(구간 시작 < 칸 끝, 구간 끝 > 칸 시작).
    return ranges.some((range) => range.from < minuteMs + 60 * 1000 && range.to > minuteMs);
}

/** 1분 통계 목록에서 그 시각이 속한 1분 항목. 없으면 null. items는 {at: ms, ...}. */
export function findMinute(items, ms) {
    // 분 시작 시각이 같은 항목을 찾는다.
    const key = minuteStart(ms);
    return items.find((item) => item.at === key) ?? null;
}

/**
 * API 응답의 항목을 계산하기 쉬운 형태로. 시각은 밀리초로 바꾸고 유입·유출은 방향 합으로 만든다.
 * 통계가 없는 분(sampleSec=0, 값은 null)은 버린다. 그 자리가 비어 시간축 선이 끊기고 '-'로 보인다(SCR-C-004 끊김 규칙).
 */
export function normalizeSeries(items) {
    // 유입·유출은 방향별 값을 담은 객체(예: 입구별)로 오므로 값을 모두 더한다. 숫자가 아닌 값은 0으로 본다.
    const sum = (flow) =>
        flow ? Object.values(flow).reduce((total, value) => total + (Number(value) || 0), 0) : 0;
    return items
        // 표본이 없는 분은 버린다. 그래야 선이 이어지지 않고 끊겨 보인다.
        .filter((item) => item.sampleSec !== 0)
        .map((item) => ({
            // 시각 문자열을 밀리초로 바꾸고 분 단위로 맞춘다.
            at: minuteStart(Date.parse(item.at)),
            countAvg: item.countAvg,
            countMax: item.countMax,
            countMin: item.countMin,
            density: item.density,
            level: item.level,
            inflow: sum(item.inflow),
            outflow: sum(item.outflow),
            sampleSec: item.sampleSec,
        }))
        // 시각이 읽히지 않는 항목은 버리고, 시간 순으로 정렬한다.
        .filter((item) => !Number.isNaN(item.at))
        .sort((first, second) => first.at - second.at);
}

/** 이벤트 응답을 밀리초 시각으로. 진행 중이면 endedAt은 null. */
/**
 * 녹화 구간 조회 응답의 ranges를 {from, to}(ms)로 바꾼다. 시각이 읽히지 않는 항목은 버린다.
 * @param {{from: string, to: string}[]} items
 */
export function normalizeRanges(items) {
    return items
        // 문자열 시각을 밀리초로 바꾼다.
        .map((item) => ({ from: Date.parse(item.from), to: Date.parse(item.to) }))
        .filter(
            // 시각이 올바르고 시작이 끝보다 앞선 구간만 남긴다.
            (item) => Number.isFinite(item.from) && Number.isFinite(item.to) && item.from < item.to,
        );
}

export function normalizeEvents(items) {
    return items
        .map((event) => ({
            id: event.id,
            type: event.type,
            startedAt: Date.parse(event.startedAt),
            // 종료 시각이 없으면 아직 진행 중인 이벤트라서 null로 둔다.
            endedAt: event.endedAt ? Date.parse(event.endedAt) : null,
            peakLevel: event.peakLevel,
        }))
        .filter((event) => !Number.isNaN(event.startedAt))
        .sort((first, second) => first.startedAt - second.startedAt);
}

/**
 * 시간축 범위 [from, to] 안에서 시작한 이벤트. 시간축의 시작 표시(▼)와 같은 기준이라, 이전·다음 이벤트 버튼은 이 이벤트들로만
 * 이동한다(FNC-A-047). 범위 앞에서 시작해 이어진 이벤트(overlap)는 구간만 그려지고 ▼가 없으므로 뺀다.
 */
export function eventsStartedIn(events, from, to) {
    // 범위의 양 끝을 포함해 시작 시각이 그 안에 있는 이벤트만 고른다.
    return events.filter((event) => event.startedAt >= from && event.startedAt <= to);
}

/** 그 시각이 걸쳐 있는 이벤트(시작 ≤ 시각, 끝이 없거나 시각 < 끝). 없으면 null. */
export function eventAt(events, ms) {
    return (
        events.find(
            (event) => event.startedAt <= ms && (event.endedAt === null || ms < event.endedAt),
        ) ?? null
    );
}

/** 시각 앞쪽에서 가장 가까운 이벤트 시작. 같은 순간(±1초)은 건너뛴다. */
export function previousEvent(events, ms) {
    // 1초의 여유를 둔 이유: 지금 위치가 이벤트 시작과 거의 같은 순간일 때 같은 이벤트로 다시 돌아가지 않게 하려는 것이다. events는 시작 시각 순서로 정렬돼 있다고 가정한다.
    const earlier = events.filter((event) => event.startedAt < ms - 1000);
    return earlier.length === 0 ? null : earlier[earlier.length - 1];
}

/** 시각 뒤쪽에서 가장 가까운 이벤트 시작. */
export function nextEvent(events, ms) {
    // 정렬된 목록에서 처음 만나는 '지금보다 뒤' 이벤트가 가장 가까운 다음 이벤트다.
    return events.find((event) => event.startedAt > ms + 1000) ?? null;
}

// 세로 범위를 데이터 최댓값보다 이만큼 크게 잡아 선이 위 테두리에 붙지 않게 한다
const CHART_HEADROOM = 1.1;

// 그래프 종류별로 어떤 항목을 선(line), 두 번째 선(second), 띠의 아래·위(low/high)로 쓸지 정한다. 인원은 평균선+최소~최대 띠, 밀도는 한 선, 유동은 유입·유출 두 선이다.
const METRICS = {
    count: { line: 'countAvg', low: 'countMin', high: 'countMax' },
    density: { line: 'density' },
    flow: { line: 'inflow', second: 'outflow' },
};

/**
 * 그래프에 그릴 선·띠 좌표를 SVG 좌표계(가로 0~width, 세로 0~height, 위가 큰 값)로 만든다.
 * 통계가 이어지지 않은 곳(2분 넘게 빈 구간)에서는 선을 끊는다. 세로 범위는 데이터 최댓값보다 조금 위까지 잡는다.
 * @param {{key: string, value: number}[]} [thresholds] 주의·위험 경계 값(그 항목의 단위). 그래프 범위 안에 드는 것만 guides로 돌려준다
 * @returns {{lines: {key: string, segments: number[][][]}[], band: number[][] | null, guides: {key: string, y: number}[], max: number}}
 */
export function buildChart(items, metricKey, from, to, width, height, thresholds = []) {
    // 모르는 종류면 인원(count)으로 대체한다.
    const metric = METRICS[metricKey] ?? METRICS.count;
    // 시간축 범위 안의 항목만 그린다.
    const visible = items.filter((item) => item.at >= from && item.at <= to);
    const keys = [metric.line, metric.second, metric.high].filter(Boolean);
    // 세로 축 크기를 정하려고 그릴 값 중 최댓값을 구한다(0을 함께 넣어 데이터가 없어도 안전하게).
    const dataMax = Math.max(
        0,
        ...visible.flatMap((item) => keys.map((key) => Number(item[key]) || 0)),
    );
    // 최댓값이 0이면 1로 대신해 0으로 나누지 않게 하고, 약간의 여유(HEADROOM)를 곱한다.
    const max = (dataMax || 1) * CHART_HEADROOM;
    // x: 시각을 0~width 위치로, y: 값을 세로 위치로 바꾼다. SVG는 위쪽이 0이므로 값이 클수록 위로 가게 height에서 뺀다.
    const x = (ms) => fraction(ms, from, to) * width;
    const y = (value) => height - (Math.max(0, Number(value) || 0) / max) * height;

    // 그릴 선 항목마다 끊어진 구간(segments)별 좌표 목록을 만든다.
    const lineKeys = [metric.line, metric.second].filter(Boolean);
    const lines = lineKeys.map((key) => ({
        key,
        segments: splitSegments(visible, (item) => [x(item.at), y(item[key])]),
    }));

    // 최소~최대 띠가 필요한 그래프(인원)만 만든다. 위쪽 경계를 시간 순으로, 아래쪽 경계를 거꾸로 이어 닫힌 도형 하나로 만든다.
    let band = null;
    if (metric.low && visible.length > 0) {
        const upper = visible.map((item) => [x(item.at), y(item[metric.high])]);
        const lower = visible.map((item) => [x(item.at), y(item[metric.low])]).reverse();
        band = [...upper, ...lower];
    }
    // 데이터가 있고 경계가 그래프 범위 안일 때만 경계선을 그린다(경계가 한참 위면 평평한 선만 남아 보기 어렵다)
    const guides =
        visible.length === 0
            ? []
            : thresholds
                  .filter((t) => Number(t.value) > 0 && Number(t.value) <= max)
                  .map((t) => ({ key: t.key, y: y(t.value) }));
    return { lines, band, guides, max };
}

// 이어진 항목끼리 한 선분으로 묶는다(항목 사이가 2분을 넘으면 끊김)
function splitSegments(items, toPoint) {
    const segments = [];
    // current: 지금 만드는 선분, previous: 직전 항목의 시각.
    let current = [];
    let previous = null;
    for (const item of items) {
        // 직전 항목과 2분을 넘게 벌어졌으면 그 사이 통계가 없는 것이므로 선분을 끊는다.
        if (previous !== null && item.at - previous > 2 * MINUTE) {
            segments.push(current);
            current = [];
        }
        current.push(toPoint(item));
        previous = item.at;
    }
    if (current.length > 0) {
        segments.push(current);
    }
    return segments;
}

/** 점 목록을 SVG path 문자열로. 점이 하나뿐이면 아주 짧은 선으로 그려 보이게 한다. */
export function toPath(points) {
    // 점이 없으면 빈 경로.
    if (points.length === 0) {
        return '';
    }
    // 점이 하나뿐이면 선이 보이지 않으므로 0.5만큼 옆에 점을 하나 더해 짧은 선으로 그린다.
    const list = points.length === 1 ? [points[0], [points[0][0] + 0.5, points[0][1]]] : points;
    return list
        // 첫 점은 이동(M), 이후는 선 긋기(L). 좌표는 소수 첫째 자리까지만 써서 문자열을 줄인다.
        .map(([px, py], index) => `${index === 0 ? 'M' : 'L'}${px.toFixed(1)} ${py.toFixed(1)}`)
        .join(' ');
}
