// 실시간 보기 창(SCR-A-008) 영상 아래 그래프 2행 2열과 '1시간 순증감'(결정 0016).
// GET /api/v1/stats/series(구역, 1m, 최근 60분) 하나로 네 그래프를 그린다. 통계 그래프(SCR-C-004)의 1분 단위와 같은 API라
// 같은 분이면 같은 값이고, 단계는 서버가 판정한 level을 그대로 쓴다. 서버 값은 textContent로만 넣는다.
import { get, url } from '../common/api.js';
import { formatNumber } from '../common/format.js';
import { countLine, flowBars, levelBars, niceMax } from './mini-charts.js';

const SERIES_API = '/api/v1/stats/series';
const MINUTE_MS = 60_000;
const WINDOW_MINUTES = 60;
const RECENT_MINUTES = 10;
const BAR_HEIGHT = 90;
const COUNT_HEIGHT = 80;
const FLOW_HEIGHT = 55;
const DIRECTIONS = [
    ['N', '북'],
    ['E', '동'],
    ['S', '남'],
    ['W', '서'],
];

let latestRequestId = 0;

/** 그래프를 비운다(창을 닫을 때). 늦게 온 응답은 그리지 않는다 */
export function clearLiveGraphs() {
    latestRequestId++;
}

/**
 * 캠의 구역 최근 60분을 읽어 그래프와 1시간 순증감을 그린다. 실패하면 마지막 그래프를 두고 문구만 보인다.
 * @param {{zoneId: number, name: string}} camera GET /api/v1/manage/cameras 항목
 */
export async function loadLiveGraphs(camera) {
    if (!document.getElementById('live-graphs')) {
        return;
    }
    const to = new Date(Math.floor(Date.now() / MINUTE_MS) * MINUTE_MS + MINUTE_MS);
    const from = new Date(to.getTime() - WINDOW_MINUTES * MINUTE_MS);
    const query = {
        targetType: 'ZONE',
        targetId: String(camera.zoneId),
        interval: '1m',
        from: toInputValue(from),
        to: toInputValue(to),
    };
    byId('live-stats-link').href = `${url('/stats/graph')}?${new URLSearchParams(query).toString()}`;
    const requestId = ++latestRequestId;
    try {
        const response = await get(SERIES_API, query, { toast: false });
        if (requestId !== latestRequestId) {
            return;
        }
        render(response.items, camera.name);
        byId('live-graph-error').hidden = true;
    } catch {
        if (requestId === latestRequestId) {
            byId('live-graph-error').hidden = false;
        }
    }
}

function render(items, cameraName) {
    const points = items.map((point) => ({
        value: point.countAvg === null || point.countAvg === undefined ? null : Number(point.countAvg),
        level: point.level ?? null,
        inflow: sum(point.inflow),
        outflow: sum(point.outflow),
    }));
    // 위 줄 두 막대는 y축 범위를 같게(10분 막대와 1시간 막대 높이를 바로 비교)
    const max = niceMax(points.map((point) => point.value));
    const recent = points.slice(-RECENT_MINUTES);
    byId('live-graph-10m').replaceChildren(levelBars(recent, { height: BAR_HEIGHT, max, label: '최근 10분 인원, 혼잡 단계 색' }));
    byId('live-graph-60m').replaceChildren(levelBars(points, { height: BAR_HEIGHT, max, label: '최근 1시간 인원, 혼잡 단계 색' }));
    byId('live-graph-count').replaceChildren(countLine(points, { height: COUNT_HEIGHT, max, label: '최근 1시간 인원' }));
    byId('live-graph-flow').replaceChildren(flowBars(points, { height: FLOW_HEIGHT, label: '최근 1시간 유입·유출' }));

    const totals = DIRECTIONS.map(([key, name]) => ({
        name,
        inflow: items.reduce((total, point) => total + (point.inflow?.[key] ?? 0), 0),
        outflow: items.reduce((total, point) => total + (point.outflow?.[key] ?? 0), 0),
    }));
    renderCompass(totals, cameraName, points);
    renderDirectionBars(totals);

    const hasFlow = points.some((point) => point.inflow !== null || point.outflow !== null);
    const net = totals.reduce((total, direction) => total + direction.inflow - direction.outflow, 0);
    byId('live-net').textContent = hasFlow ? `${net > 0 ? '+' : net < 0 ? '−' : ''}${formatNumber(Math.abs(net))}명` : '-';
}

// 가운데 구역, 둘레에 방향별 유입(↓·←·↑·→ 안쪽)·유출 숫자
function renderCompass(totals, cameraName, points) {
    const [north, east, south, west] = totals;
    const latest = [...points].reverse().find((point) => point.value !== null);
    const center = cell('center');
    const name = document.createElement('b');
    name.textContent = cameraName;
    const count = document.createElement('div');
    count.className = 'muted';
    count.textContent = latest ? `${formatNumber(Math.round(latest.value))}명` : '-';
    center.append(name, count);
    byId('live-compass').replaceChildren(
        document.createElement('span'), direction(north, '↓', '↑'), document.createElement('span'),
        direction(west, '→', '←'), center, direction(east, '←', '→'),
        document.createElement('span'), direction(south, '↑', '↓'), document.createElement('span'),
    );
}

function direction(total, inArrow, outArrow) {
    const box = cell('dir');
    const label = document.createElement('div');
    label.className = 'muted';
    label.textContent = total.name;
    const inflow = document.createElement('div');
    inflow.className = 'in';
    inflow.textContent = `${inArrow}${formatNumber(total.inflow)}`;
    const outflow = document.createElement('div');
    outflow.className = 'out';
    outflow.textContent = `${outArrow}${formatNumber(total.outflow)}`;
    box.append(label, inflow, outflow);
    return box;
}

function renderDirectionBars(totals) {
    const max = Math.max(1, ...totals.flatMap((total) => [total.inflow, total.outflow]));
    byId('live-direction-bars').replaceChildren(...totals.map((total) => {
        const row = cell('row');
        const name = document.createElement('span');
        name.className = 'muted';
        name.textContent = total.name;
        const bars = cell('bars');
        bars.append(bar('in', total.inflow, max, `유입 ${formatNumber(total.inflow)}명`), bar('out', total.outflow, max, `유출 ${formatNumber(total.outflow)}명`));
        row.append(name, bars);
        return row;
    }));
}

function bar(kind, value, max, title) {
    const element = document.createElement('div');
    element.className = `bar ${kind}`;
    element.style.width = `${(value / max) * 100}%`;
    element.title = title;
    return element;
}

function cell(className) {
    const element = document.createElement('div');
    element.className = className;
    return element;
}

// 방향별(N·E·S·W) 합. 값이 없는 점은 null
function sum(flow) {
    return flow ? Object.values(flow).reduce((total, value) => total + value, 0) : null;
}

const pad = (value) => String(value).padStart(2, '0');

// 통계 그래프 주소 쿼리와 같은 'yyyy-MM-ddTHH:mm'(브라우저 시각)
function toInputValue(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function byId(id) {
    return document.getElementById(id);
}
