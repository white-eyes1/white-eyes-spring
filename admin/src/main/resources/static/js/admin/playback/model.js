// 다시보기 화면(SCR-A-009)의 계산만 모아 둔 파일. 화면(DOM)을 건드리지 않아 따로 시험할 수 있다.
// 시각은 모두 밀리초(UTC 기준 epoch)로 다루고, 화면에 보일 때만 한국 시간(KST)으로 바꾼다.

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const KST_OFFSET = 9 * HOUR;

/** 시간축에 보이는 범위(밀리초). '오늘'은 windowFor에서 그 날 0시~24시로 따로 계산한다. */
export const RANGE_MS = { '30m': 30 * MINUTE, '1h': HOUR, '3h': 3 * HOUR };

const pad = (value) => String(value).padStart(2, '0');

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
export function minuteStart(ms) {
    return Math.floor(ms / MINUTE) * MINUTE;
}

/**
 * 시간축 범위. 30분·1시간·3시간은 center를 가운데로, 'today'는 center가 속한 날 0시~24시(한국 시간).
 * @returns {{from: number, to: number}}
 */
export function windowFor(rangeKey, center) {
    if (rangeKey === 'today') {
        const dayStart = Math.floor((center + KST_OFFSET) / DAY) * DAY - KST_OFFSET;
        return { from: dayStart, to: dayStart + DAY };
    }
    const span = RANGE_MS[rangeKey] ?? RANGE_MS['1h'];
    const from = minuteStart(center - span / 2);
    return { from, to: from + span };
}

/** 범위 안 위치를 0~1로. 범위 밖이면 0 또는 1로 잘라 준다. */
export function fraction(ms, from, to) {
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
    return recording.startedAt <= ms && (recording.endedAt === null || ms < recording.endedAt);
}

/** 1분 통계 목록에서 그 시각이 속한 1분 항목. 없으면 null. items는 {at: ms, ...}. */
export function findMinute(items, ms) {
    const key = minuteStart(ms);
    return items.find((item) => item.at === key) ?? null;
}

/** API 응답의 항목을 계산하기 쉬운 형태로. 시각은 밀리초로 바꾸고 유입·유출은 방향 합으로 만든다. */
export function normalizeSeries(items) {
    const sum = (flow) => (flow ? Object.values(flow).reduce((total, value) => total + (Number(value) || 0), 0) : 0);
    return items
        .map((item) => ({
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
        .filter((item) => !Number.isNaN(item.at))
        .sort((first, second) => first.at - second.at);
}

/** 이벤트 응답을 밀리초 시각으로. 진행 중이면 endedAt은 null. */
export function normalizeEvents(items) {
    return items
        .map((event) => ({
            id: event.id,
            type: event.type,
            startedAt: Date.parse(event.startedAt),
            endedAt: event.endedAt ? Date.parse(event.endedAt) : null,
            peakLevel: event.peakLevel,
        }))
        .filter((event) => !Number.isNaN(event.startedAt))
        .sort((first, second) => first.startedAt - second.startedAt);
}

/** 그 시각이 걸쳐 있는 이벤트(시작 ≤ 시각, 끝이 없거나 시각 < 끝). 없으면 null. */
export function eventAt(events, ms) {
    return events.find((event) => event.startedAt <= ms && (event.endedAt === null || ms < event.endedAt)) ?? null;
}

/** 시각 앞쪽에서 가장 가까운 이벤트 시작. 같은 순간(±1초)은 건너뛴다. */
export function previousEvent(events, ms) {
    const earlier = events.filter((event) => event.startedAt < ms - 1000);
    return earlier.length === 0 ? null : earlier[earlier.length - 1];
}

/** 시각 뒤쪽에서 가장 가까운 이벤트 시작. */
export function nextEvent(events, ms) {
    return events.find((event) => event.startedAt > ms + 1000) ?? null;
}

const METRICS = {
    count: { line: 'countAvg', low: 'countMin', high: 'countMax' },
    density: { line: 'density' },
    flow: { line: 'inflow', second: 'outflow' },
};

/**
 * 그래프에 그릴 선·띠 좌표를 SVG 좌표계(가로 0~width, 세로 0~height, 위가 큰 값)로 만든다.
 * 통계가 이어지지 않은 곳(2분 넘게 빈 구간)에서는 선을 끊는다.
 * @returns {{lines: {key: string, segments: number[][][]}[], band: number[][] | null, max: number}}
 */
export function buildChart(items, metricKey, from, to, width, height) {
    const metric = METRICS[metricKey] ?? METRICS.count;
    const visible = items.filter((item) => item.at >= from && item.at <= to);
    const keys = [metric.line, metric.second, metric.high].filter(Boolean);
    const max =
        Math.max(
            0,
            ...visible.flatMap((item) => keys.map((key) => Number(item[key]) || 0)),
        ) || 1;
    const x = (ms) => fraction(ms, from, to) * width;
    const y = (value) => height - (Math.max(0, Number(value) || 0) / max) * height;

    const lineKeys = [metric.line, metric.second].filter(Boolean);
    const lines = lineKeys.map((key) => ({ key, segments: splitSegments(visible, (item) => [x(item.at), y(item[key])]) }));

    let band = null;
    if (metric.low && visible.length > 0) {
        const upper = visible.map((item) => [x(item.at), y(item[metric.high])]);
        const lower = visible.map((item) => [x(item.at), y(item[metric.low])]).reverse();
        band = [...upper, ...lower];
    }
    return { lines, band, max };
}

// 이어진 항목끼리 한 선분으로 묶는다(항목 사이가 2분을 넘으면 끊김)
function splitSegments(items, toPoint) {
    const segments = [];
    let current = [];
    let previous = null;
    for (const item of items) {
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
    if (points.length === 0) {
        return '';
    }
    const list = points.length === 1 ? [points[0], [points[0][0] + 0.5, points[0][1]]] : points;
    return list.map(([px, py], index) => `${index === 0 ? 'M' : 'L'}${px.toFixed(1)} ${py.toFixed(1)}`).join(' ');
}
