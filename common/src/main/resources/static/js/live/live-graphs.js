// 실시간 보기 창(SCR-A-008) 영상 아래 그래프 2행 2열과 '1시간 순증감'(결정 0016).
// GET /api/v1/stats/series(구역, 1m, 최근 60분) 하나로 네 그래프를 그린다. 통계 그래프(SCR-C-004)의 1분 단위와 같은 API라
// 같은 분이면 같은 값이고, 단계는 서버가 판정한 level을 그대로 쓴다. 서버 값은 textContent로만 넣는다.
import { get, url } from '../common/api.js';
import { formatNumber } from '../common/format.js';
import { countLine, flowBars, levelBars, niceMax } from './mini-charts.js';

// 구역 시계열 API. 통계 그래프와 같은 API를 쓴다
const SERIES_API = '/api/v1/stats/series';
// 1분(밀리초)
const MINUTE_MS = 60_000;
// 최근 60분을 읽는다
const WINDOW_MINUTES = 60;
// 위 줄 왼쪽 그래프는 그중 최근 10분만 보인다
const RECENT_MINUTES = 10;
// 각 미니 그래프의 높이(px)
const BAR_HEIGHT = 90;
const COUNT_HEIGHT = 80;
const FLOW_HEIGHT = 55;
// 방향 코드(N·E·S·W)와 화면 이름
const DIRECTIONS = [
    ['N', '북'],
    ['E', '동'],
    ['S', '남'],
    ['W', '서'],
];

// 요청마다 올리는 번호. 창을 닫거나 다른 캠으로 바꾼 뒤 늦게 도착한 이전 응답을 버리려고 쓴다
let latestRequestId = 0;

/** 그래프를 비운다(창을 닫거나 다른 캠으로 열 때). 늦게 온 응답은 그리지 않는다 */
export function clearLiveGraphs() {
    // 번호를 올려 진행 중인 요청의 응답이 그려지지 않게 한다
    latestRequestId++;
    // 이 화면에 그래프 영역이 없으면 할 일이 없다
    if (!document.getElementById('live-graphs')) {
        return;
    }
    // 그래프 칸들의 내용을 지운다
    [
        'live-graph-10m',
        'live-graph-60m',
        'live-graph-count',
        'live-graph-flow',
        'live-compass',
        'live-direction-bars',
    ].forEach((id) => byId(id).replaceChildren());
    // 순증감도 '-'로 되돌린다
    byId('live-net').textContent = '-';
}

/**
 * 캠의 구역 최근 60분을 읽어 그래프와 1시간 순증감을 그린다. 실패하면 마지막 그래프를 두고 문구만 보인다.
 * @param {{zoneId: number, name: string}} camera GET /api/v1/manage/cameras 항목
 */
export async function loadLiveGraphs(camera) {
    // 이 화면에 그래프 영역이 없으면 읽지 않는다
    if (!document.getElementById('live-graphs')) {
        return;
    }
    // 현재 분의 끝(다음 분 경계)을 to로: 진행 중인 분까지 포함한다
    const to = new Date(Math.floor(Date.now() / MINUTE_MS) * MINUTE_MS + MINUTE_MS);
    // 그 60분 전이 from
    const from = new Date(to.getTime() - WINDOW_MINUTES * MINUTE_MS);
    // 구역 1분 단위 시계열을 요청하는 조건. 통계 그래프 화면 링크에도 그대로 쓴다
    const query = {
        targetType: 'ZONE',
        targetId: String(camera.zoneId),
        interval: '1m',
        from: toInputValue(from),
        to: toInputValue(to),
    };
    // '통계 그래프에서 보기' 링크를 같은 조건으로 연다
    byId('live-stats-link').href =
        `${url('/stats/graph')}?${new URLSearchParams(query).toString()}`;
    // 이 요청의 번호. 응답이 왔을 때 최신 요청인지 비교한다
    const requestId = ++latestRequestId;
    try {
        // 오류 토스트는 끄고 화면 안 문구(live-graph-error)로 알린다
        const response = await get(SERIES_API, query, { toast: false });
        // 그 사이 창이 닫히거나 다른 캠이 열렸으면 이 응답은 버린다
        if (requestId !== latestRequestId) {
            return;
        }
        render(response.items, camera.name);
        byId('live-graph-error').hidden = true;
    } catch {
        // 최신 요청이 실패했을 때만 오류 문구를 보인다
        if (requestId === latestRequestId) {
            byId('live-graph-error').hidden = false;
        }
    }
}

// 응답 점들을 그래프용 값으로 바꾸고 각 칸을 그린다
function render(items, cameraName) {
    // 점마다 인원 평균(없으면 null), 혼잡 단계, 유입·유출 합계
    const points = items.map((point) => ({
        // 값이 없는 점은 0이 아니라 null: 그래프에서 '데이터 없음'과 '0명'을 구분하기 위해서다
        value:
            point.countAvg === null || point.countAvg === undefined ? null : Number(point.countAvg),
        level: point.level ?? null,
        inflow: sum(point.inflow),
        outflow: sum(point.outflow),
    }));
    // 위 줄 두 막대는 y축 범위를 같게(10분 막대와 1시간 막대 높이를 바로 비교)
    const max = niceMax(points.map((point) => point.value));
    // 최근 10분 = 마지막 10개 점(1분 단위라서)
    const recent = points.slice(-RECENT_MINUTES);
    byId('live-graph-10m').replaceChildren(
        levelBars(recent, { height: BAR_HEIGHT, max, label: '최근 10분 인원, 혼잡 단계 색' }),
    );
    byId('live-graph-60m').replaceChildren(
        levelBars(points, { height: BAR_HEIGHT, max, label: '최근 1시간 인원, 혼잡 단계 색' }),
    );
    byId('live-graph-count').replaceChildren(
        countLine(points, { height: COUNT_HEIGHT, max, label: '최근 1시간 인원' }),
    );
    byId('live-graph-flow').replaceChildren(
        flowBars(points, { height: FLOW_HEIGHT, label: '최근 1시간 유입·유출' }),
    );

    // 방향별로 60분 동안의 유입·유출을 합친다. 값이 없는 점은 0으로 본다
    const totals = DIRECTIONS.map(([key, name]) => ({
        name,
        inflow: items.reduce((total, point) => total + (point.inflow?.[key] ?? 0), 0),
        outflow: items.reduce((total, point) => total + (point.outflow?.[key] ?? 0), 0),
    }));
    renderCompass(totals, cameraName, points);
    renderDirectionBars(totals);

    // 유입·유출 값이 하나라도 있었는지: 없으면 순증감을 '-'로 보인다
    const hasFlow = points.some((point) => point.inflow !== null || point.outflow !== null);
    // 순증감 = 전체 유입 - 전체 유출
    const net = totals.reduce(
        (total, direction) => total + direction.inflow - direction.outflow,
        0,
    );
    // 부호를 붙여 보인다(감소는 빼기 기호 −)
    byId('live-net').textContent = hasFlow
        ? `${net > 0 ? '+' : net < 0 ? '−' : ''}${formatNumber(Math.abs(net))}명`
        : '-';
}

// 가운데 구역, 둘레에 방향별 유입(↓·←·↑·→ 안쪽)·유출 숫자
// 방향 나침반: 3x3 칸의 가운데에 구역, 위·아래·왼·오른쪽 칸에 방향별 숫자. 모서리 칸은 빈 span
function renderCompass(totals, cameraName, points) {
    // totals 순서는 DIRECTIONS와 같다(북·동·남·서)
    const [north, east, south, west] = totals;
    // 가장 최근에 값이 있는 점의 인원을 가운데에 보인다(뒤에서부터 찾기 위해 뒤집는다)
    const latest = [...points].reverse().find((point) => point.value !== null);
    const center = cell('center');
    const name = document.createElement('b');
    name.textContent = cameraName;
    const count = document.createElement('div');
    count.className = 'muted';
    count.textContent = latest ? `${formatNumber(Math.round(latest.value))}명` : '-';
    center.append(name, count);
    // 3x3 격자를 위에서 아래, 왼쪽에서 오른쪽 순서로 채운다. 화살표는 그 방향 칸에서 가운데로 들어오는/나가는 모양
    byId('live-compass').replaceChildren(
        document.createElement('span'),
        direction(north, '↓', '↑'),
        document.createElement('span'),
        direction(west, '→', '←'),
        center,
        direction(east, '←', '→'),
        document.createElement('span'),
        direction(south, '↑', '↓'),
        document.createElement('span'),
    );
}

// 한 방향 칸: 이름, 유입, 유출. 화살표 두 개는 칸 위치에 따라 달라서 호출한 쪽이 정해 준다
function direction(total, inArrow, outArrow) {
    const box = cell('dir');
    const label = document.createElement('div');
    label.className = 'muted';
    label.textContent = total.name;
    const inflow = document.createElement('div');
    inflow.className = 'in';
    // 숫자는 textContent로만 넣는다
    inflow.textContent = `${inArrow}${formatNumber(total.inflow)}`;
    const outflow = document.createElement('div');
    outflow.className = 'out';
    outflow.textContent = `${outArrow}${formatNumber(total.outflow)}`;
    box.append(label, inflow, outflow);
    return box;
}

// 방향별 유입·유출 가로 막대. 막대 길이는 가장 큰 값을 100%로 맞춘 비율이다
function renderDirectionBars(totals) {
    // 값이 모두 0이어도 0으로 나누지 않도록 최소 1
    const max = Math.max(1, ...totals.flatMap((total) => [total.inflow, total.outflow]));
    byId('live-direction-bars').replaceChildren(
        ...totals.map((total) => {
            const row = cell('row');
            const name = document.createElement('span');
            name.className = 'muted';
            name.textContent = total.name;
            const bars = cell('bars');
            bars.append(
                bar('in', total.inflow, max, `유입 ${formatNumber(total.inflow)}명`),
                bar('out', total.outflow, max, `유출 ${formatNumber(total.outflow)}명`),
            );
            row.append(name, bars);
            return row;
        }),
    );
}

// 막대 하나. 길이는 max 대비 비율, title은 마우스를 올렸을 때 보이는 숫자
function bar(kind, value, max, title) {
    const element = document.createElement('div');
    element.className = `bar ${kind}`;
    element.style.width = `${(value / max) * 100}%`;
    element.title = title;
    return element;
}

// 클래스만 다른 div를 만드는 작은 도우미
function cell(className) {
    const element = document.createElement('div');
    element.className = className;
    return element;
}

// 방향별(N·E·S·W) 합. 값이 없는 점은 null
// 방향별 유입·유출 객체의 값을 모두 더한다. 객체가 없는 점(통계 없음)은 null
function sum(flow) {
    return flow ? Object.values(flow).reduce((total, value) => total + value, 0) : null;
}

// 한 자리 숫자를 두 자리로
const pad = (value) => String(value).padStart(2, '0');

// 통계 그래프 주소 쿼리와 같은 'yyyy-MM-ddTHH:mm'(브라우저 시각)
function toInputValue(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// document.getElementById 줄임
function byId(id) {
    return document.getElementById(id);
}
