// 통계 패널의 보조 지표 접기 영역 6개(관리자 앱 7개, SCR-C-003, FNC-C-030). 새 API·새 집계 없이 기존 응답으로 화면에서 계산한다.
// 방향별 유입·유출, 평균 체류, 인원 변동폭, 관측 상태(수신율)는 GET /api/v1/stats/series(1m, 최근 1시간),
// 평균 체류의 시간별 추이는 같은 API(1h, 오늘 0시~현재), 오늘 단계별 머문 시간은 같은 API(1m, 오늘 0시~현재)로 읽는다.
// 혼잡 구역 TOP 5와 관측 상태(구역 수)는 화면이 이미 읽은 GET /api/v1/map/status?unit=ZONE 결과를 쓴다.
// 오늘 이벤트 종류별 건수(관리자 앱만)는 GET /api/v1/events/summary를 한 번 읽어 종류별 건수(byType)와 미확인(unchecked)을 쓴다.
// 시계열은 펼친 영역에 필요한 것만 읽고, 새 1분 통계(통계 SSE, FNC-C-022)가 오면 펼쳐 있는 영역만 다시 읽는다.
import { get } from '../../common/api.js';
import { formatDecimal, formatNumber } from '../../common/format.js';
import { levelColor } from '../../map/map-view.js';

// 시계열 API(통계)와 이벤트 요약 API
const SERIES_API = '/api/v1/stats/series';
const EVENT_SUMMARY_API = '/api/v1/events/summary';
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';
// SVG 요소를 만들 때 쓰는 이름공간
const SVG_NS = 'http://www.w3.org/2000/svg';
// 시간 계산용 상수: 1분(밀리초), 1시간의 분 수, 1시간의 초 수
const MINUTE_MS = 60_000;
const HOUR_MINUTES = 60;
const SECONDS_PER_HOUR = 3600;
// 혼잡 구역 순위로 보이는 개수
const TOP_ZONES = 5;
// 방향 코드 -> 화면 이름
const DIRECTIONS = [
    ['N', '북(N)'],
    ['E', '동(E)'],
    ['S', '남(S)'],
    ['W', '서(W)'],
];
// 작은 그래프의 가상 가로 폭과 색들
const GRAPH_WIDTH = 360;
const DWELL_COLOR = '#a78bfa';
const SPREAD_COLOR = '#76a7ff';
const SPREAD_BAND_COLOR = 'rgba(79,140,255,.22)';
const RECEIVE_COLOR = '#76a7ff';
// 관리자 앱에만 있는 접기 영역(이벤트 요약 API가 관리자 이상). 종류 코드·이름·칩 색(conventions.md event.type, 시안)
const EVENT_FOLD = 'aux-events';
const EVENT_TYPES = [
    ['CONGESTION', '혼잡', 'l3'],
    ['SURGE', '급증', 'surge'],
    ['CAM_FAULT', '캠 이상', 'cam'],
];

// 접기 영역마다 필요한 시계열. lastHour=1m 최근 1시간, todayHourly=1h 오늘, todayMinute=1m 오늘
const FOLDS = {
    'aux-flow': ['lastHour'],
    'aux-dwell': ['lastHour', 'todayHourly'],
    'aux-spread': ['lastHour'],
    'aux-level-time': ['todayMinute'],
    'aux-top-zones': [],
    'aux-observe': ['lastHour'],
};

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

/**
 * @param {object[]} levels GET /meta/levels (단계 이름·색)
 */
// 보조 지표 패널 하나를 만든다. 화면은 setRegion(지역)과 refresh(구역 목록)를 부른다
export function createAuxMetricsPanel(levels) {
    // regionId: 현재 지역. 요청 번호들: 늦게 온 응답을 버리려는 값
    let regionId = null;
    let latestRequestId = 0;
    let latestEventRequestId = 0;
    const eventFold = byId(EVENT_FOLD);

    // 펼칠 때는 펼쳐 있는 영역을 함께 읽는다. 앞선 요청의 결과는 버리므로 방금 펼친 영역만 읽으면 다른 영역이 빈다
    // 접기 영역을 펼칠 때 펼쳐 있는 모든 영역의 값을 함께 읽는다
    Object.keys(FOLDS).forEach((id) => {
        byId(id).addEventListener('toggle', () => {
            if (byId(id).open) {
                loadSeries(openFolds());
            }
        });
    });

    // 이벤트 요약 영역은 관리자 앱에만 있어 없을 수 있다
    eventFold?.addEventListener('toggle', () => {
        if (eventFold.open) {
            loadEvents();
        }
    });

    /** 통계 대상 지역이 바뀌면 부른다 */
    function setRegion(id) {
        regionId = id;
    }

    /**
     * 화면이 값을 새로 읽을 때마다(지역 변경·새 1분 통계·60초 확인) 부른다.
     * @param {object[]} items GET /map/status?unit=ZONE 의 items
     */
    function refresh(items) {
        // 이미 읽은 구역 목록으로 계산하는 두 영역(혼잡 구역 순위, 관측 상태)을 다시 그린다
        renderTopZones(items);
        renderObservedZones(items);
        // 시계열이 필요한 영역은 펼쳐 있는 것만 다시 읽는다
        loadSeries(openFolds());
        if (eventFold?.open) {
            loadEvents();
        }
    }

    function openFolds() {
        // 펼쳐 있는(open) 접기 영역의 id
        return Object.keys(FOLDS).filter((id) => byId(id).open);
    }

    async function loadSeries(foldIds) {
        // 필요한 시계열 종류를 중복 없이 모은다
        const kinds = [...new Set(foldIds.flatMap((id) => FOLDS[id]))];
        // 지역이 없거나 읽을 시계열이 없으면 요청하지 않는다
        if (regionId === null || kinds.length === 0) {
            return;
        }
        // 요청 번호로 마지막 요청의 응답만 반영한다
        const requestId = ++latestRequestId;
        // 이번에 읽을 기간(최근 1시간, 오늘 시간별, 오늘 분 단위)
        const ranges = seriesRanges(new Date());
        try {
            // 종류마다 병렬로 읽는다
            const entries = await Promise.all(
                kinds.map(async (kind) => [kind, await readSeries(regionId, ranges[kind])]),
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역 변경 등)
            }
            const series = Object.fromEntries(entries);
            foldIds.forEach((id) => renderFold(id, series));
        // 실패하면 시계열 영역에만 오류 문구를 보인다(구역 목록으로 계산하는 영역은 그대로)
        } catch {
            if (requestId === latestRequestId) {
                foldIds
                    .filter((id) => FOLDS[id].length > 0)
                    .forEach((id) => showMessage(id, LOAD_ERROR));
            }
        }
    }

    async function loadEvents() {
        // 지역이 없으면 읽지 않는다
        if (regionId === null) {
            return;
        }
        const requestId = ++latestEventRequestId;
        const now = new Date();
        // 오늘 0시부터 지금까지의 이벤트 요약
        const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        try {
            const summary = await readEventSummary(regionId, midnight, now);
            if (requestId === latestEventRequestId) {
                renderEvents(summary);
            }
        } catch {
            if (requestId === latestEventRequestId) {
                showMessage(EVENT_FOLD, LOAD_ERROR);
            }
        }
    }

    // 영역 id에 맞는 그리기 함수를 고른다
    function renderFold(id, series) {
        switch (id) {
            case 'aux-flow':
                renderFlow(series.lastHour);
                break;
            case 'aux-dwell':
                renderDwell(series.lastHour, series.todayHourly);
                break;
            case 'aux-spread':
                renderSpread(series.lastHour);
                break;
            case 'aux-level-time':
                renderLevelTime(series.todayMinute, levels);
                break;
            case 'aux-observe':
                renderReceiveRate(series.lastHour);
                break;
            default:
                break;
        }
    }

    return { setRegion, refresh };
}

// 최근 1시간은 끝난 분까지 60개 구간(지금 분은 아직 집계 전). 오늘은 0시부터 지금 분까지
// 읽을 기간 계산. 지금 분은 아직 집계가 안 됐을 수 있어 끝난 분까지만 쓴다
function seriesRanges(now) {
    // 지금 분의 시작 시각(밀리초 단위를 분 단위로 내림)
    const minuteEnd = new Date(Math.floor(now.getTime() / MINUTE_MS) * MINUTE_MS);
    // 오늘 0시
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return {
        lastHour: {
            interval: '1m',
            from: new Date(minuteEnd.getTime() - HOUR_MINUTES * MINUTE_MS),
            to: minuteEnd,
        },
        todayHourly: { interval: '1h', from: midnight, to: minuteEnd },
        todayMinute: { interval: '1m', from: midnight, to: minuteEnd },
    };
}

// 0시 정각처럼 기간이 비면 읽지 않는다(API는 끝이 시작보다 뒤여야 한다)
// readSeries: 지역·단위·기간으로 시계열을 읽어 점 목록만 돌려준다
async function readSeries(regionId, { interval, from, to }) {
    // 기간이 비어 있으면 서버가 거절하므로 빈 목록
    if (to <= from) {
        return [];
    }
    const params = {
        targetType: 'REGION',
        targetId: String(regionId),
        interval,
        // API는 ISO 시각 문자열을 받는다
        from: from.toISOString(),
        to: to.toISOString(),
    };
    const data = await get(SERIES_API, params, { toast: false });
    return data.items;
}

/** 통계가 있는 점(값이 null이 아닌 점)만 */
// 인원 평균이 있는 점만(= 통계가 있는 분)
function sampled(points) {
    return points.filter((point) => point.countAvg !== null);
}

// ---- 방향별 유입·유출 (추정) -----------------------------------------------------------------

function renderFlow(points) {
    // 통계가 있는 점만 쓴다
    const rows = sampled(points);
    if (rows.length === 0) {
        showMessage('aux-flow', NO_DATA);
        return;
    }
    // 방향별 합: 점마다 유입·유출 객체에서 그 방향 값을 더한다(없는 값은 0)
    const sum = (key, direction) =>
        rows.reduce((total, point) => total + (point[key]?.[direction] ?? 0), 0);
    const totals = DIRECTIONS.map(([code, label]) => ({
        label,
        inflow: sum('inflow', code),
        outflow: sum('outflow', code),
    }));
    // 막대 길이의 기준이 되는 가장 큰 값(0으로 나누지 않게 최소 1)
    const max = Math.max(...totals.flatMap((total) => [total.inflow, total.outflow]), 1);
    byId('aux-flow-body').replaceChildren(
        ...totals.map(({ label, inflow, outflow }) => {
            const row = document.createElement('div');
            row.className = 'row';
            const name = document.createElement('span');
            name.className = 'dir';
            name.textContent = label;
            const amount = document.createElement('span');
            amount.className = 'num';
            amount.textContent = `+${formatNumber(inflow)} / −${formatNumber(outflow)}`;
            // 한 줄: 방향 이름, 유입 막대, 유출 막대, 숫자
            row.append(
                name,
                bar(inflow / max, 'var(--lv1)', true),
                bar(outflow / max, 'var(--lv3)', false),
                amount,
            );
            return row;
        }),
    );
}

// 유입은 가운데에서 왼쪽으로, 유출은 오른쪽으로 자라는 막대(시안 SCR-C-003-stats-panel-all)
// 막대 하나. ratio는 0~1 비율, alignEnd면 오른쪽에서 왼쪽으로 자란다
function bar(ratio, color, alignEnd) {
    const track = document.createElement('div');
    track.className = alignEnd ? 'bar flow-bar end' : 'bar flow-bar';
    const fill = document.createElement('i');
    fill.style.width = `${Math.round(ratio * 100)}%`;
    fill.style.background = color;
    track.append(fill);
    return track;
}

// ---- 평균 체류 시간 (추정) -------------------------------------------------------------------

// 최근 1시간 값은 분마다의 평균 체류를 다시 평균한다(응답에 체류를 잰 사람 수가 없어 가중 평균을 못 한다)
function renderDwell(lastHour, todayHourly) {
    // 분 단위 평균 체류가 있는 점만
    const minuteValues = lastHour
        .map((point) => point.dwellAvgSec)
        .filter((value) => value !== null);
    // 오늘 시간별 값(없으면 null: 선이 끊긴다)
    const hourlyValues = todayHourly.map((point) =>
        point.dwellAvgSec === null ? null : Number(point.dwellAvgSec),
    );
    // 두 자료가 모두 비면 '데이터 없음'
    if (minuteValues.length === 0 && hourlyValues.every((value) => value === null)) {
        showMessage('aux-dwell', NO_DATA);
        return;
    }
    // 최근 1시간의 분 값을 다시 평균
    const average =
        minuteValues.length === 0
            ? null
            : minuteValues.reduce((total, value) => total + Number(value), 0) / minuteValues.length;
    const head = document.createElement('div');
    head.className = 'row';
    const value = document.createElement('strong');
    value.className = 'big';
    value.textContent = average === null ? '-' : formatDuration(average);
    const note = document.createElement('span');
    note.className = 'muted';
    note.textContent = '최근 1시간 · 아래는 오늘 시간별';
    head.append(value, note);
    // 위에 숫자 한 줄, 아래에 오늘 시간별 선 그래프
    byId('aux-dwell-body').replaceChildren(
        head,
        lineGraph(hourlyValues, DWELL_COLOR, 50, '오늘 시간별 평균 체류'),
    );
}

/** 초를 'n분 n초'(1분 미만은 'n초')로 */
function formatDuration(seconds) {
    // 초를 반올림해 분·초로 나눈다
    const rounded = Math.round(seconds);
    const minutes = Math.floor(rounded / 60);
    return minutes > 0 ? `${minutes}분 ${rounded % 60}초` : `${rounded}초`;
}

// ---- 인원 변동폭 ---------------------------------------------------------------------------

// 분마다 최대~최소를 띠로, 평균 인원을 선으로 그린다. 띠의 두께가 그 분의 변동폭(countMax − countMin)이다
function renderSpread(points) {
    const rows = sampled(points);
    if (rows.length === 0) {
        showMessage('aux-spread', NO_DATA);
        return;
    }
    // 분마다 변동폭 = 최대 - 최소
    const spreads = rows.map((point) => point.countMax - point.countMin);
    // 가장 최근 분의 변동폭
    const latest = spreads[spreads.length - 1];
    const summary = document.createElement('div');
    summary.className = 'row';
    // 요약 줄: 최근 1분 변동폭, 최근 1시간 중 최대 변동폭
    summary.append(
        labeled('최근 1분 변동폭', `${formatNumber(latest)}명`),
        labeled('1시간 최대', `${formatNumber(Math.max(...spreads))}명`),
    );
    const note = document.createElement('span');
    note.className = 'muted';
    note.textContent = '폭이 넓을수록 1분 안에서 인원이 크게 출렁임 → 급증 전조';
    // 요약 줄, 띠 그래프, 설명 문구를 차례로 보인다
    byId('aux-spread-body').replaceChildren(summary, bandGraph(points), note);
}

function labeled(label, value) {
    const span = document.createElement('span');
    span.className = 'muted';
    const strong = document.createElement('b');
    strong.textContent = value;
    span.append(`${label} `, strong);
    return span;
}

// ---- 오늘 단계별 머문 시간 -------------------------------------------------------------------

// 오늘 1분 통계의 단계를 세어 분 단위로 보인다(통계 없는 분은 세지 않는다)
function renderLevelTime(points, levels) {
    // 단계(level)마다 오늘 1분 통계 중 그 단계였던 분 수
    const minutes = levels.map(({ level, name }) => ({
        level,
        name,
        count: points.filter((point) => point.level === level).length,
    }));
    // 모두 0이면 '데이터 없음'
    if (minutes.every((entry) => entry.count === 0)) {
        showMessage('aux-level-time', NO_DATA);
        return;
    }
    // 단계별 분 수 비율로 폭이 정해지는 가로 막대(flex 값이 분 수)
    const stack = document.createElement('div');
    stack.className = 'level-stack';
    const labels = document.createElement('div');
    labels.className = 'level-time muted';
    minutes.forEach(({ level, name, count }) => {
        // 분이 있는 단계만 막대 조각을 만든다. 라벨은 분이 0이어도 보인다
        if (count > 0) {
            const part = document.createElement('span');
            part.style.flex = String(count);
            part.style.background = levelColor(level);
            stack.append(part);
        }
        const label = document.createElement('span');
        label.textContent = `${name} ${formatMinutes(count)}`;
        labels.append(label);
    });
    byId('aux-level-time-body').replaceChildren(stack, labels);
}

/** 분을 'n시간 n분'(1시간 미만은 'n분')으로 */
// 분을 시간·분으로
function formatMinutes(total) {
    const hours = Math.floor(total / 60);
    return hours > 0 ? `${hours}시간 ${total % 60}분` : `${total}분`;
}

// ---- 혼잡 구역 TOP 5 ------------------------------------------------------------------------

// 최근 1분 통계가 없는 구역(level null)은 빼고 밀집도 내림차순 상위 5개. 한 줄에 이름·평균 인원·밀집도
function renderTopZones(items) {
    // 혼잡 구역 순위: 밀집도 내림차순 상위 N개
    const ranked = items
        // 1분 통계가 없는 구역은 순위에서 뺀다
        .filter((item) => item.level !== null && item.density !== null)
        .sort((a, b) => Number(b.density) - Number(a.density))
        .slice(0, TOP_ZONES);
    if (ranked.length === 0) {
        showMessage('aux-top-zones', NO_DATA);
        return;
    }
    byId('aux-top-zones-body').replaceChildren(
        ...ranked.map((item, index) => {
            const row = document.createElement('div');
            row.className = 'row';
            const rank = document.createElement('b');
            rank.className = 'rank';
            rank.textContent = String(index + 1);
            const name = document.createElement('span');
            name.className = 'name';
            name.textContent = item.name;
            row.append(rank, name);
            // 구역이 넓으면 밀집도가 모두 0.00이 되므로 평균 인원도 같이 적는다
            if (item.countAvg !== null) {
                const count = document.createElement('span');
                count.className = 'count muted';
                count.textContent = `${formatNumber(Math.round(Number(item.countAvg)))}명`;
                row.append(count);
            }
            const chip = document.createElement('span');
            chip.className = `chip l${item.level}`;
            chip.textContent = formatDecimal(item.density, 2);
            row.append(chip);
            return row;
        }),
    );
}

// ---- 오늘 이벤트 종류별 건수(관리자 앱) ----------------------------------------------------------

// 종류를 거르지 않고 한 번 읽는다. 응답의 byType에 세 종류가 항상 들어 있다
// 종류 구분 없이 오늘 하루 전체를 읽는다
function readEventSummary(regionId, from, to) {
    const params = {
        from: from.toISOString(),
        to: to.toISOString(),
        regionId: String(regionId),
    };
    return get(EVENT_SUMMARY_API, params, { toast: false });
}

// 종류마다 건수 칩, 그 아래 미확인 건수. 오늘 이벤트가 없으면 '데이터 없음'
function renderEvents(summary) {
    // 오늘 이벤트가 없으면 영역에 '데이터 없음'
    if (summary.total === 0) {
        showMessage(EVENT_FOLD, NO_DATA);
        return;
    }
    const chips = document.createElement('div');
    chips.className = 'row';
    // 종류마다 칩 하나(건수가 없으면 0)
    EVENT_TYPES.forEach(([type, label, chipClass]) => {
        const chip = document.createElement('span');
        chip.className = `chip ${chipClass}`;
        chip.textContent = `${label} ${formatNumber(summary.byType[type] ?? 0)}`;
        chips.append(chip);
    });
    const unchecked = document.createElement('div');
    unchecked.className = 'row';
    const name = document.createElement('span');
    name.className = 'grow';
    name.textContent = '미확인';
    const count = document.createElement('b');
    count.className = 'aux-unchecked';
    count.textContent = `${formatNumber(summary.unchecked)}건`;
    unchecked.append(name, count);
    byId(`${EVENT_FOLD}-body`).replaceChildren(chips, unchecked);
}

// ---- 관측 상태 -----------------------------------------------------------------------------

// 통계가 있는 구역 수 / 전체 구역 수. 지역 값은 이 구역들만의 값이다(알려진 한계 19)
function renderObservedZones(items) {
    // 통계가 있는 구역(statAt이 있는 구역) 수를 센다
    const observed = items.filter((item) => item.statAt !== null).length;
    byId('aux-observe-zones').textContent =
        items.length === 0 ? NO_DATA : `${formatNumber(observed)} / ${formatNumber(items.length)}`;
}

// 최근 1시간 sampleSec 합 / 3600. 지역의 sampleSec은 합산한 구역 중 가장 큰 값이다
function renderReceiveRate(points) {
    // 최근 1시간에 수신한 초 합
    const receivedSec = points.reduce((total, point) => total + point.sampleSec, 0);
    const box = byId('aux-observe-rate');
    if (receivedSec === 0) {
        box.replaceChildren(NO_DATA);
        return;
    }
    // 3600초를 넘지 않게 1로 자른다
    const rate = Math.min(receivedSec / SECONDS_PER_HOUR, 1);
    const track = document.createElement('div');
    track.className = 'bar';
    const fill = document.createElement('i');
    fill.style.width = `${Math.round(rate * 100)}%`;
    fill.style.background = RECEIVE_COLOR;
    track.append(fill);
    const value = document.createElement('b');
    value.textContent = `${Math.round(rate * 100)}%`;
    box.replaceChildren(track, value);
}

// ---- 작은 그래프(SVG) ----------------------------------------------------------------------

// SVG 그래프 틀. 가로는 칸에 맞춰 늘어난다
function svgGraph(height, label) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${GRAPH_WIDTH} ${height}`);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('class', 'aux-graph');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', label);
    svg.style.height = `${height}px`;
    return svg;
}

// 점마다 x 위치. 점이 하나면 가운데
// 점 위치. 점이 하나면 가운데에 둔다
function xAt(index, count) {
    return count < 2 ? GRAPH_WIDTH / 2 : (index / (count - 1)) * GRAPH_WIDTH;
}

// 값이 없는 점(null)에서 선이 끊긴다
// 값이 없는 점(null)에서 선이 끊기는 선 그래프
function lineGraph(values, color, height, label) {
    const svg = svgGraph(height, label);
    // 값이 하나도 없으면 빈 틀만
    const present = values.filter((value) => value !== null);
    if (present.length === 0) {
        return svg;
    }
    // 0이 최대일 때 나누기 방지로 1
    const max = Math.max(...present) || 1;
    // 값을 위쪽이 큰 y 좌표로 바꾼다(SVG는 위에서 아래로 커진다)
    const yAt = (value) => height - 3 - (value / max) * (height - 6);
    // 이어진 구간마다 선을 그린다. 한 점만 있는 구간은 점(원)으로 그린다
    segments(values).forEach((segment) => {
        const line = document.createElementNS(SVG_NS, segment.length === 1 ? 'circle' : 'polyline');
        if (segment.length === 1) {
            const [index] = segment;
            line.setAttribute('cx', xAt(index, values.length).toFixed(1));
            line.setAttribute('cy', yAt(values[index]).toFixed(1));
            line.setAttribute('r', '2');
            line.setAttribute('fill', color);
        } else {
            const xy = (index) =>
                `${xAt(index, values.length).toFixed(1)},${yAt(values[index]).toFixed(1)}`;
            line.setAttribute('points', segment.map(xy).join(' '));
            line.setAttribute('fill', 'none');
            line.setAttribute('stroke', color);
            line.setAttribute('stroke-width', '2');
            line.setAttribute('vector-effect', 'non-scaling-stroke');
        }
        svg.append(line);
    });
    return svg;
}

// 최대~최소 띠 + 평균 선. 통계 없는 분에서 끊긴다
// 최대~최소 띠와 평균 선
function bandGraph(points) {
    const height = 60;
    const svg = svgGraph(height, '1분마다 최대·최소 인원 범위');
    const present = sampled(points);
    // 띠의 높이는 최대값 기준
    const max = Math.max(...present.map((point) => point.countMax)) || 1;
    const yAt = (value) => height - 3 - (value / max) * (height - 6);
    // 이어진 구간마다 띠(다각형)와 선을 만든다
    segments(points.map((point) => point.countAvg)).forEach((segment) => {
        const xy = (index, value) =>
            `${xAt(index, points.length).toFixed(1)},${yAt(value).toFixed(1)}`;
        // 띠의 윗변은 최대값, 아랫변은 최소값을 거꾸로 이어 닫는다
        const top = segment.map((index) => xy(index, points[index].countMax));
        const bottom = [...segment].reverse().map((index) => xy(index, points[index].countMin));
        const band = document.createElementNS(SVG_NS, 'polygon');
        band.setAttribute('points', [...top, ...bottom].join(' '));
        band.setAttribute('fill', SPREAD_BAND_COLOR);
        const line = document.createElementNS(SVG_NS, 'polyline');
        line.setAttribute(
            'points',
            segment.map((index) => xy(index, Number(points[index].countAvg))).join(' '),
        );
        line.setAttribute('fill', 'none');
        line.setAttribute('stroke', SPREAD_COLOR);
        line.setAttribute('stroke-width', '2');
        line.setAttribute('vector-effect', 'non-scaling-stroke');
        svg.append(band, line);
    });
    return svg;
}

// null이 아닌 점이 이어진 구간마다 점 번호 목록
// 값이 있는 점의 순번이 이어진 구간으로 나눈다(null이 구간을 자른다)
function segments(values) {
    const result = [];
    let current = [];
    values.forEach((value, index) => {
        if (value === null) {
            if (current.length > 0) {
                result.push(current);
            }
            current = [];
        } else {
            current.push(index);
        }
    });
    if (current.length > 0) {
        result.push(current);
    }
    return result;
}

// 영역마다 '데이터 없음' 또는 읽기 실패 문구. 관측 상태는 구역 수 줄을 두고 수신율 칸에만 쓴다
function showMessage(foldId, text) {
    // 관측 상태 영역은 구역 수가 따로 있어 수신율 칸에만 문구를 넣는다
    if (foldId === 'aux-observe') {
        byId('aux-observe-rate').replaceChildren(text);
        return;
    }
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = text;
    byId(`${foldId}-body`).replaceChildren(empty);
}
