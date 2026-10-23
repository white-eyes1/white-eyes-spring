// 통계 그래프 진입 파일 SCR-C-004(FNC-C-012): 대상·단위·기간·항목을 고르면 GET /api/v1/stats/series로 그린다.
// 대상·단위·기간은 주소 쿼리(targetType·targetId·interval·from·to)로 유지한다. 기본은 1h, 오늘(screens.md SCR-C-004).
// 시각은 브라우저 시각을 오프셋 없이 주고받는다(서버가 KST로 읽음). 끝 칸은 to에서 1분 뺀 시각을 보인다(#25에서 정함).
import { ApiError, get } from '../common/api.js';
import { formatDateTime } from '../common/format.js';
import { SERIES_ITEMS, createSeriesChart } from '../chart/series-chart.js';

const SERIES_API = '/api/v1/stats/series';
const LEVEL_LABELS = ['시·도', '시·군·구', '읍·면·동'];
const DEFAULT_INTERVAL = '1h';
const MINUTE_MS = 60_000;
// 단위별 이름과 최대 조회 기간(conventions.md STATS_MAX_RANGE, 서버 StatInterval과 같은 값)
const INTERVALS = {
    '1m': { label: '1분', max: { days: 1 }, maxLabel: '1일' },
    '1h': { label: '1시간', max: { days: 31 }, maxLabel: '31일' },
    '1d': { label: '1일', max: { years: 1 }, maxLabel: '1년' },
    '1w': { label: '1주', max: { years: 3 }, maxLabel: '3년' },
    '1M': { label: '1월', max: { years: 5 }, maxLabel: '5년' },
    '1y': { label: '1년', max: { years: 10 }, maxLabel: '10년' },
};
const FIELD_LABELS = { targetType: '대상', targetId: '대상', interval: '단위', from: '시작', to: '끝' };
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';

const byId = (id) => document.getElementById(id);
const regionSelects = ['target-sido', 'target-sigungu', 'target-emd'].map(byId);
const zoneSelect = byId('target-zone');
const tabs = document.querySelectorAll('#interval-tabs [data-interval]');
const itemBoxes = document.querySelectorAll('#graph-items input[type="checkbox"]');
const fromInput = byId('period-from');
const endInput = byId('period-end');
const errorNotice = byId('graph-error');
const emptyNotice = byId('graph-empty');
const chart = createSeriesChart(byId('series-chart'));

const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록
const zonesCache = new Map(); // dongId -> 구역 목록
let path = []; // 시·도부터 고른 지역 목록
let zone = null; // 고른 구역(없으면 지역이 대상)
let interval = DEFAULT_INTERVAL;
let from = null;
let to = null;
let items = [];
// 조건을 빠르게 여러 번 바꾸면 마지막 요청의 결과만 그린다
let latestRequestId = 0;

start();

async function start() {
    const params = new URLSearchParams(window.location.search);
    interval = INTERVALS[params.get('interval')] ? params.get('interval') : DEFAULT_INTERVAL;
    const fromParam = parseLocal(params.get('from'));
    const toParam = parseLocal(params.get('to'));
    if (fromParam && toParam) {
        from = fromParam;
        to = toParam;
    } else {
        setDefaultPeriod();
    }
    await selectTarget(params.get('targetType'), Number(params.get('targetId')));
    initTabs();
    initItems();
    byId('period-apply').addEventListener('click', applyPeriod);
    renderTabs();
    renderPeriod();
    await load();
}

// ---- 대상(시·구·동·구역) ------------------------------------------------------------------------

async function children(parentId) {
    const key = parentId ?? 'root';
    if (!childrenCache.has(key)) {
        childrenCache.set(key, await get('/api/v1/regions', parentId === null ? undefined : { parentId }));
    }
    return childrenCache.get(key);
}

async function zonesOf(dongId) {
    if (!zonesCache.has(dongId)) {
        zonesCache.set(dongId, await get('/api/v1/zones', { dongId }));
    }
    return zonesCache.get(dongId);
}

// 통계 메인과 같은 방법: 상위 지역을 되묻는 API가 없어 위에서부터 한 단계씩 내려가며 찾는다
async function findPath(regionId) {
    let frontier = (await children(null)).map((region) => [region]);
    for (let depth = 0; depth < LEVEL_LABELS.length && frontier.length > 0; depth++) {
        const hit = frontier.find((candidate) => candidate[depth].id === regionId);
        if (hit) {
            return hit;
        }
        if (depth === LEVEL_LABELS.length - 1) {
            break;
        }
        const next = [];
        for (const candidate of frontier) {
            const kids = await children(candidate[depth].id);
            kids.forEach((kid) => next.push([...candidate, kid]));
        }
        frontier = next;
    }
    return null;
}

async function selectTarget(targetType, targetId) {
    let found = null;
    zone = null;
    if (targetType === 'ZONE' && targetId) {
        // 구역은 소속 동을 알아야 경로를 찾을 수 있어 전체 구역에서 찾는다
        zone = (await get('/api/v1/zones')).find((candidate) => candidate.id === targetId) ?? null;
        found = zone ? await findPath(zone.dongId) : null;
        if (!found) {
            zone = null;
        }
    } else if (targetType === 'REGION' && targetId) {
        found = await findPath(targetId);
    }
    if (!found) {
        // 처음엔 시 단위(통계 메인과 같음)
        const top = await children(null);
        found = top.length > 0 ? [top[0]] : [];
    }
    path = found;
    await renderTargetSelects();
}

async function renderTargetSelects() {
    for (let depth = 0; depth < regionSelects.length; depth++) {
        const select = regionSelects[depth];
        const options = depth === 0 ? await children(null) : path[depth - 1] ? await children(path[depth - 1].id) : [];
        select.replaceChildren(...optionList(options, depth > 0 ? `${LEVEL_LABELS[depth]} 전체` : null));
        select.value = path[depth] ? String(path[depth].id) : '';
        select.disabled = options.length === 0 || (depth > 0 && !path[depth - 1]);
        select.onchange = () => onRegionChange(depth, select.value);
    }
    const dong = path[LEVEL_LABELS.length - 1];
    const zones = dong ? await zonesOf(dong.id) : [];
    zoneSelect.replaceChildren(...optionList(zones, '구역 전체'));
    zoneSelect.value = zone ? String(zone.id) : '';
    zoneSelect.disabled = zones.length === 0;
    zoneSelect.onchange = () => onZoneChange(zones, zoneSelect.value);
}

function optionList(entries, allLabel) {
    const list = entries.map((entry) => {
        const option = document.createElement('option');
        option.value = String(entry.id);
        option.textContent = entry.name;
        return option;
    });
    if (allLabel) {
        const all = document.createElement('option');
        all.value = '';
        all.textContent = allLabel;
        list.unshift(all);
    }
    return list;
}

async function onRegionChange(depth, value) {
    const base = path.slice(0, depth);
    if (value !== '') {
        const options = depth === 0 ? await children(null) : await children(path[depth - 1].id);
        base.push(options.find((region) => String(region.id) === value));
    }
    path = base;
    zone = null;
    await renderTargetSelects();
    await load();
}

async function onZoneChange(zones, value) {
    const previous = zone;
    zone = zones.find((candidate) => String(candidate.id) === value) ?? null;
    if (previous?.deleted && zone?.id !== previous.id) {
        // 삭제된 구역을 떠나면 선택 칸에서 '(삭제됨)' 항목을 뺀다
        await renderTargetSelects();
    }
    await load();
}

function currentTarget() {
    if (zone) {
        return { targetType: 'ZONE', targetId: zone.id };
    }
    return { targetType: 'REGION', targetId: path[path.length - 1].id };
}

// ---- 단위·기간·항목 ----------------------------------------------------------------------------

function initTabs() {
    tabs.forEach((tab) => {
        tab.addEventListener('click', () => {
            interval = tab.dataset.interval;
            // 단위를 바꾸면 기간을 그 단위의 기본값으로(screens.md SCR-C-004 단위)
            setDefaultPeriod();
            renderTabs();
            renderPeriod();
            load();
        });
    });
}

function renderTabs() {
    tabs.forEach((tab) => {
        const on = tab.dataset.interval === interval;
        tab.classList.toggle('on', on);
        tab.setAttribute('aria-selected', String(on));
    });
}

// 1m: 최근 1시간, 1h: 오늘, 1d: 최근 30일, 1w: 최근 12주, 1M: 최근 12개월, 1y: 최근 5년
function setDefaultPeriod() {
    const now = new Date();
    const tomorrow = startOfDay(now);
    tomorrow.setDate(tomorrow.getDate() + 1);
    switch (interval) {
        case '1m': {
            to = new Date(Math.floor(now.getTime() / MINUTE_MS) * MINUTE_MS + MINUTE_MS);
            from = new Date(to.getTime() - 60 * MINUTE_MS);
            break;
        }
        case '1h':
            from = startOfDay(now);
            to = tomorrow;
            break;
        case '1d':
            to = tomorrow;
            from = shift(tomorrow, { days: -30 });
            break;
        case '1w': {
            // 1w는 월요일 시작(conventions.md '통계 단위')
            const monday = startOfDay(now);
            monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
            from = shift(monday, { days: -7 * 11 });
            to = tomorrow;
            break;
        }
        case '1M':
            from = shift(new Date(now.getFullYear(), now.getMonth(), 1), { months: -11 });
            to = tomorrow;
            break;
        default:
            from = new Date(now.getFullYear() - 4, 0, 1);
            to = tomorrow;
    }
}

function renderPeriod() {
    fromInput.value = toInputValue(from);
    endInput.value = toInputValue(new Date(to.getTime() - MINUTE_MS));
}

function applyPeriod() {
    const newFrom = parseLocal(fromInput.value);
    const end = parseLocal(endInput.value);
    if (!newFrom || !end) {
        showError('시작·끝 시각을 입력해 주세요.');
        return;
    }
    from = newFrom;
    to = new Date(end.getTime() + MINUTE_MS);
    load();
}

// 항목은 하나 이상 골라야 한다(screens.md SCR-C-004 항목). 다시 읽지 않고 받은 값으로 다시 그린다
function initItems() {
    itemBoxes.forEach((box) => {
        box.addEventListener('change', () => {
            if (selectedItems().length === 0) {
                box.checked = true;
                return;
            }
            render();
        });
    });
}

function selectedItems() {
    return [...itemBoxes].filter((box) => box.checked).map((box) => box.value);
}

// ---- 조회·그리기 ----------------------------------------------------------------------------------

/** 화면에서 먼저 검사한다. 틀리면 문구를 보이고 조회하지 않는다. */
function validatePeriod() {
    if (to <= from) {
        return '끝 시각은 시작 시각보다 뒤여야 합니다.';
    }
    const spec = INTERVALS[interval];
    if (to > shift(from, spec.max)) {
        return `${spec.label} 단위는 최대 ${spec.maxLabel}까지 조회할 수 있습니다.`;
    }
    return null;
}

async function load() {
    if (path.length === 0) {
        return;
    }
    const invalid = validatePeriod();
    if (invalid) {
        showError(invalid);
        return;
    }
    const target = currentTarget();
    const query = { ...target, interval, from: toInputValue(from), to: toInputValue(to) };
    const url = new URL(window.location.href);
    Object.entries(query).forEach(([name, value]) => url.searchParams.set(name, String(value)));
    window.history.replaceState(null, '', url);

    const requestId = ++latestRequestId;
    hideError();
    try {
        // 오류는 그래프 위 문구로 보이므로 api.js의 토스트는 끈다
        const response = await get(SERIES_API, query, { toast: false });
        if (requestId === latestRequestId) {
            items = response.items;
            render();
        }
    } catch (error) {
        if (requestId === latestRequestId) {
            items = [];
            render();
            showError(errorText(error));
        }
    }
}

function render() {
    const keys = selectedItems();
    const seriesByItem = {};
    keys.forEach((key) => {
        // 인원은 평균 선과 최대~최소 띠
        seriesByItem[key] = key === 'count'
            ? {
                values: items.map((point) => valueOf(point, 'countAvg')),
                high: items.map((point) => valueOf(point, 'countMax')),
                low: items.map((point) => valueOf(point, 'countMin')),
            }
            : { values: items.map((point) => valueOf(point, key)) };
    });
    chart.update(items.map((point) => axisLabel(point.at)), items.map((point) => tooltipTitle(point.at)), seriesByItem);
    // 오류 문구가 보이는 동안에는 '데이터 없음'을 같이 보이지 않는다
    emptyNotice.hidden = !errorNotice.hidden || items.some((point) => point.sampleSec > 0);
}

// 유입·유출은 방향(N·E·S·W) 합. 값이 없는 점은 null(선 끊김)
function valueOf(point, key) {
    if (key === 'inflow' || key === 'outflow') {
        const flow = point[key];
        return flow ? Object.values(flow).reduce((sum, value) => sum + value, 0) : null;
    }
    const value = point[key];
    return value === null || value === undefined ? null : Number(value);
}

// x축: 1m·1h는 시각(하루를 넘으면 날짜도), 1d·1w는 월-일, 1M은 연-월, 1y는 연
function axisLabel(at) {
    const text = formatDateTime(at); // yyyy-MM-dd HH:mm
    switch (interval) {
        case '1m':
        case '1h':
            return to.getTime() - from.getTime() > 24 * 60 * MINUTE_MS ? text.slice(5) : text.slice(11);
        case '1d':
        case '1w':
            return text.slice(5, 10);
        case '1M':
            return text.slice(0, 7);
        default:
            return text.slice(0, 4);
    }
}

// 툴팁 제목: 구간(예: 17:00 ~ 18:00)
function tooltipTitle(at) {
    const start = new Date(at);
    const text = formatDateTime(start);
    switch (interval) {
        case '1m':
            return `${text} ~ ${formatDateTime(new Date(start.getTime() + MINUTE_MS)).slice(11)}`;
        case '1h':
            return `${text} ~ ${formatDateTime(new Date(start.getTime() + 60 * MINUTE_MS)).slice(11)}`;
        case '1d':
            return text.slice(0, 10);
        case '1w':
            return `${text.slice(0, 10)} ~ ${formatDateTime(shift(start, { days: 6 })).slice(5, 10)}`;
        case '1M':
            return text.slice(0, 7);
        default:
            return text.slice(0, 4);
    }
}

function errorText(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        return DEFAULT_ERROR_MESSAGE;
    }
    if (error.fieldErrors.length > 0) {
        const details = error.fieldErrors.map(
            (fieldError) => `${FIELD_LABELS[fieldError.field] ?? fieldError.field}: ${fieldError.message}`,
        );
        return `${error.message} (${details.join(' / ')})`;
    }
    return error.message;
}

function showError(message) {
    errorNotice.textContent = message;
    errorNotice.hidden = false;
    emptyNotice.hidden = true;
}

function hideError() {
    errorNotice.hidden = true;
    errorNotice.textContent = '';
}

// ---- 시각 도우미 -------------------------------------------------------------------------------

function startOfDay(date) {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function shift(date, { years = 0, months = 0, days = 0 }) {
    const result = new Date(date.getTime());
    result.setFullYear(result.getFullYear() + years, result.getMonth() + months, result.getDate() + days);
    return result;
}

const pad = (value) => String(value).padStart(2, '0');

// datetime-local 칸과 주소 쿼리에 쓰는 'yyyy-MM-ddTHH:mm'(브라우저 시각)
function toInputValue(date) {
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}

// 'yyyy-MM-dd', 'yyyy-MM-ddTHH:mm', 'yyyy-MM-ddTHH:mm:ss'를 브라우저 시각으로 읽는다. 틀리면 null
function parseLocal(text) {
    const match = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec(text ?? '');
    if (!match) {
        return null;
    }
    const [, year, month, day, hour = '0', minute = '0', second = '0'] = match;
    return new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second));
}
