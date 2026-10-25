// 통계 그래프 진입 파일 SCR-C-004(FNC-C-012): 대상·단위·기간·항목을 고르면 GET /api/v1/stats/series로 그린다.
// 대상·단위·기간은 주소 쿼리(targetType·targetId·interval·from·to)로 유지한다. 기본은 1h, 오늘(screens.md SCR-C-004).
// 시각은 브라우저 시각을 오프셋 없이 주고받는다(서버가 KST로 읽음). 끝 칸은 to에서 1분 뺀 시각을 보인다(#25에서 정함).
// 하위 비교(FNC-C-014)를 켜면 GET /api/v1/stats/children으로 고른 항목 하나를 하위 단위별 선으로 겹쳐 그린다(대상이 지역일 때만).
// 단계별 들어가기(FNC-C-013): 점을 누르면 한 단계 아래 단위·기간으로 다시 그리고 이동 경로를 남긴다.
// 1시간 점은 1분 통계가 있을 때만 1분으로 내려간다(보관 기간 지남·시연용이면 '1분 데이터 없음'). 관리자 앱은 '1분 단위로 보기'·'다시보기'
// 중 고르고, 1분 점은 바로 다시보기(SCR-A-009)로 간다. 다시보기는 구역 영상이라 대상이 구역일 때만 열 수 있다.
import { ApiError, get, url } from '../common/api.js';
import { formatDateTime, formatDecimal, formatNumber } from '../common/format.js';
import { SERIES_ITEMS, createSeriesChart } from '../chart/series-chart.js';
import { initExportModal, openExportModal } from '../export/export-modal.js';
import { crumbLabel, drillDown } from './stats-graph/drill.js';

const SERIES_API = '/api/v1/stats/series';
const CHILDREN_API = '/api/v1/stats/children';
// 하위 비교 항목 코드(conventions.md '통계 항목') → 그래프 항목 키(SERIES_ITEMS)
const COMPARE_ITEM_KEYS = { COUNT: 'count', DENSITY: 'density', INFLOW: 'inflow', OUTFLOW: 'outflow', DWELL: 'dwellAvgSec' };
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
const FIELD_LABELS = {
    targetType: '대상',
    targetId: '대상',
    regionId: '대상',
    item: '항목',
    interval: '단위',
    from: '시작',
    to: '끝',
};
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const NO_MINUTE_MESSAGE = '1분 데이터 없음';
const PLAYBACK_ZONE_ONLY_MESSAGE = '다시보기는 구역을 골랐을 때만 볼 수 있습니다.';
const LOAD_FAILED_MESSAGE = '불러오지 못했습니다.';
// 관리자 앱만 다시보기를 연결한다(공통 헤더와 같은 판단)
const IS_ADMIN = document.querySelector('[data-header="admin"]') !== null;

const byId = (id) => document.getElementById(id);
const regionSelects = ['target-sido', 'target-sigungu', 'target-emd'].map(byId);
const zoneSelect = byId('target-zone');
const tabs = document.querySelectorAll('#interval-tabs [data-interval]');
const itemBoxes = document.querySelectorAll('#graph-items input[type="checkbox"]');
const itemGroup = byId('graph-items');
const compareToggle = byId('compare-toggle');
const compareItemSelect = byId('compare-item');
const omittedNotice = byId('compare-omitted');
const fromInput = byId('period-from');
const endInput = byId('period-end');
const errorNotice = byId('graph-error');
const emptyNotice = byId('graph-empty');
const drillPath = byId('drill-path');
const pointMenu = byId('point-menu');
const canvas = byId('series-chart');
const chart = createSeriesChart(canvas, {
    onPointClick,
    // 사용자 앱의 1분 점은 누를 수 없다(FNC-C-013 처리 순서 6)
    clickable: () => IS_ADMIN || interval !== '1m',
});

const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록
const zonesCache = new Map(); // dongId -> 구역 목록
let path = []; // 시·도부터 고른 지역 목록
let zone = null; // 고른 구역(없으면 지역이 대상)
let interval = DEFAULT_INTERVAL;
let from = null;
let to = null;
let items = [];
let comparing = false;
let compareResult = { series: [], omitted: 0 };
// 지금 그린 점들의 구간 시작 시각(응답의 at 그대로). 점을 누르면 이 값으로 아래 단계를 정한다
let pointTimes = [];
// 이동 경로: 들어가기 전 화면부터 지금 화면까지 {interval, from, to, label, clickedInterval}. 들어가기 전에는 비어 있다
let trail = [];
// 조건을 빠르게 여러 번 바꾸면 마지막 요청의 결과만 그린다
let latestRequestId = 0;
let latestMenuRequestId = 0;

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
    initCompare();
    byId('period-apply').addEventListener('click', applyPeriod);
    initExportModal();
    byId('export-open').addEventListener('click', openExport);
    initPointMenu();
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
        if (!zone) {
            // 목록에는 삭제되지 않은 구역만 있다. 삭제된 구역의 지난 통계를 주소로 열면 단건 조회로 찾아 '(삭제됨)'을 붙인다(FNC-A-017, #183)
            zone = await findDeletedZone(targetId);
        }
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
    const active = dong ? await zonesOf(dong.id) : [];
    // 삭제된 구역을 보는 중이면 그 구역도 선택 칸에 '(삭제됨)'으로 넣는다(다른 구역을 고르면 사라진다)
    const zones = zone && zone.deleted && zone.dongId === dong?.id ? [...active, zone] : active;
    zoneSelect.replaceChildren(...optionList(zones, '구역 전체'));
    zoneSelect.value = zone ? String(zone.id) : '';
    zoneSelect.disabled = zones.length === 0;
    zoneSelect.onchange = () => onZoneChange(zones, zoneSelect.value);
    renderCompareAvailability();
}

// 삭제된 구역이면 그 구역, 아니거나 없으면 null
async function findDeletedZone(zoneId) {
    try {
        const found = await get(`/api/v1/zones/${zoneId}`, undefined, { toast: false });
        return found.deleted ? found : null;
    } catch {
        return null;
    }
}

function optionList(entries, allLabel) {
    const list = entries.map((entry) => {
        const option = document.createElement('option');
        option.value = String(entry.id);
        option.textContent = entry.deleted ? `${entry.name} (삭제됨)` : entry.name;
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
    clearTrail();
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
    clearTrail();
    renderCompareAvailability();
    await load();
}

function currentTarget() {
    if (zone) {
        return { targetType: 'ZONE', targetId: zone.id };
    }
    return { targetType: 'REGION', targetId: path[path.length - 1].id };
}

// 엑셀 다운로드 창(SCR-C-006)에 현재 대상·단위·기간을 넘긴다. 구역이면 소속 동도 범위로 고를 수 있게 함께 넘긴다
function openExport() {
    if (path.length === 0) {
        return;
    }
    const region = path[path.length - 1];
    openExportModal({
        zone: zone ? { id: zone.id, name: zone.name, deleted: Boolean(zone.deleted) } : null,
        region: { id: region.id, name: region.name },
        interval,
        from,
        to,
    });
}

// ---- 단위·기간·항목 ----------------------------------------------------------------------------

function initTabs() {
    tabs.forEach((tab) => {
        tab.addEventListener('click', () => {
            interval = tab.dataset.interval;
            // 단위를 바꾸면 기간을 그 단위의 기본값으로(screens.md SCR-C-004 단위)
            setDefaultPeriod();
            clearTrail();
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
    clearTrail();
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

// ---- 하위 비교(FNC-C-014) ----------------------------------------------------------------------

function initCompare() {
    compareToggle.addEventListener('change', () => {
        comparing = compareToggle.checked;
        renderCompareControls();
        load();
    });
    // 항목마다 서버가 값을 골라 주므로 항목을 바꾸면 다시 조회한다
    compareItemSelect.addEventListener('change', load);
    renderCompareControls();
}

// 하위 비교는 대상이 지역일 때만(screens.md SCR-C-004 하위 비교). 구역을 고르면 끈다
function renderCompareAvailability() {
    const available = !zone && path.length > 0;
    compareToggle.disabled = !available;
    compareToggle.closest('label').title = available ? '' : '대상이 지역일 때만 쓸 수 있습니다';
    if (!available && comparing) {
        comparing = false;
        compareToggle.checked = false;
        renderCompareControls();
    }
}

// 켜면 항목 선택을 보이고, 위쪽 항목 체크박스는 쓰지 않으므로 흐리게 한다
function renderCompareControls() {
    compareItemSelect.hidden = !comparing;
    itemGroup.classList.toggle('is-disabled', comparing);
    itemGroup.setAttribute('aria-disabled', String(comparing));
    omittedNotice.hidden = true;
}

// ---- 단계별 들어가기(FNC-C-013) ---------------------------------------------------------------

async function onPointClick(index, position) {
    const atText = pointTimes[index];
    if (!atText) {
        return;
    }
    const at = new Date(atText);
    if (interval === '1m') {
        // 사용자 앱은 동작 없음, 관리자 앱은 다시보기(처리 순서 6)
        if (!IS_ADMIN) {
            return;
        }
        if (zone) {
            window.location.href = playbackUrl(atText);
        } else {
            showPointMenu(index, position, [], PLAYBACK_ZONE_ONLY_MESSAGE);
        }
        return;
    }
    if (interval !== '1h') {
        drillInto(at);
        return;
    }
    // 1시간 점: 1분 통계가 있어야 내려간다(업무 규칙: 보관 기간 지남·시연용 구간은 1분 데이터 없음)
    const requestId = ++latestMenuRequestId;
    if (!IS_ADMIN) {
        const available = await hasMinuteData(at);
        if (requestId !== latestMenuRequestId) {
            return;
        }
        if (available) {
            drillInto(at);
        } else {
            showPointMenu(index, position, [], available === null ? LOAD_FAILED_MESSAGE : NO_MINUTE_MESSAGE);
        }
        return;
    }
    // 관리자 앱: '1분 단위로 보기' / '다시보기'(1시간 점 선택 창). 1분 버튼은 1분 통계를 확인한 뒤 켠다
    const minuteButton = menuButton('1분 단위로 보기', () => drillInto(at));
    minuteButton.setAttribute('aria-disabled', 'true');
    const playbackLink = menuLink('다시보기', zone ? playbackUrl(atText) : null);
    showPointMenu(index, position, [minuteButton, playbackLink], zone ? null : PLAYBACK_ZONE_ONLY_MESSAGE);
    const available = await hasMinuteData(at);
    if (requestId !== latestMenuRequestId || pointMenu.hidden) {
        return;
    }
    if (available) {
        minuteButton.removeAttribute('aria-disabled');
    } else {
        addMenuNotice(available === null ? LOAD_FAILED_MESSAGE : NO_MINUTE_MESSAGE);
    }
}

// 누른 점의 한 단계 아래로 들어가고 이동 경로에 남긴다
function drillInto(at) {
    const next = drillDown(interval, at);
    if (!next) {
        return;
    }
    if (trail.length === 0) {
        trail.push({ interval, from, to, label: `${INTERVALS[interval].label} 단위`, clickedInterval: null });
    }
    const parent = trail[trail.length - 1];
    trail.push({ ...next, label: crumbLabel(interval, at, parent.clickedInterval), clickedInterval: interval });
    showView(next);
}

// 이동 경로의 한 칸을 누르면 그 단계로 돌아간다(후속 동작). 맨 앞 칸이면 경로를 지운다
function backTo(index) {
    const view = trail[index];
    trail = index === 0 ? [] : trail.slice(0, index + 1);
    showView(view);
}

function showView(view) {
    interval = view.interval;
    from = view.from;
    to = view.to;
    renderTrail();
    renderTabs();
    renderPeriod();
    load();
}

// 단위·기간·대상을 직접 바꾸면 들어간 단계 기록은 끝난다
function clearTrail() {
    trail = [];
    renderTrail();
}

// '1년 단위 › 2026년 › 10월 › 21일 › 17시'(마지막 칸이 지금 화면, 굵게)
function renderTrail() {
    const parts = [];
    trail.forEach((step, index) => {
        if (index > 0) {
            const separator = document.createElement('span');
            separator.setAttribute('aria-hidden', 'true');
            separator.textContent = '›';
            parts.push(separator);
        }
        if (index === trail.length - 1) {
            const current = document.createElement('b');
            current.setAttribute('aria-current', 'page');
            current.textContent = step.label;
            parts.push(current);
        } else {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = step.label;
            button.addEventListener('click', () => backTo(index));
            parts.push(button);
        }
    });
    drillPath.replaceChildren(...parts);
}

// 그 시간에 받은 1분 통계가 하나라도 있으면 true, 없으면 false, 읽지 못하면 null
async function hasMinuteData(at) {
    const hour = drillDown('1h', at);
    try {
        const response = await get(
            SERIES_API,
            { ...currentTarget(), interval: '1m', from: toInputValue(hour.from), to: toInputValue(hour.to) },
            { toast: false },
        );
        return response.items.some((point) => point.sampleSec > 0);
    } catch {
        return null;
    }
}

function playbackUrl(atText) {
    return `${url('/playback')}?zoneId=${zone.id}&at=${encodeURIComponent(atText)}`;
}

function menuButton(label, onClick) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = label;
    button.addEventListener('click', () => {
        if (button.getAttribute('aria-disabled') !== 'true') {
            onClick();
        }
    });
    return button;
}

// 주소가 없으면(대상이 지역) 누를 수 없는 링크로 둔다
function menuLink(label, href) {
    const link = document.createElement('a');
    link.className = 'btn secondary sm';
    link.textContent = label;
    if (href) {
        link.href = href;
    } else {
        link.setAttribute('aria-disabled', 'true');
    }
    return link;
}

function initPointMenu() {
    // 그래프 밖을 누르거나 Esc를 누르면 닫는다. 그래프 안을 누르면 점 클릭이 새로 띄운다
    document.addEventListener('click', (event) => {
        if (!pointMenu.hidden && !pointMenu.contains(event.target) && event.target !== canvas) {
            hidePointMenu();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            hidePointMenu();
        }
    });
}

// 누른 점 옆에 구간·값 요약과 버튼(또는 안내)을 띄운다. 오른쪽 끝에서는 점 왼쪽에 띄운다
function showPointMenu(index, position, actions, notice) {
    byId('point-menu-title').textContent = tooltipTitle(pointTimes[index]);
    const summary = byId('point-menu-summary');
    summary.textContent = comparing ? '' : pointSummary(items[index]);
    summary.hidden = summary.textContent === '';
    byId('point-menu-actions').replaceChildren(...actions);
    const noticeLine = byId('point-menu-notice');
    noticeLine.textContent = notice ?? '';
    noticeLine.hidden = !notice;
    pointMenu.hidden = false;
    const box = pointMenu.parentElement;
    const x = canvas.offsetLeft + position.x;
    const y = canvas.offsetTop + position.y;
    const gap = 12;
    const left = x + gap + pointMenu.offsetWidth > box.clientWidth ? x - gap - pointMenu.offsetWidth : x + gap;
    pointMenu.style.left = `${Math.max(left, 0)}px`;
    pointMenu.style.top = `${Math.max(Math.min(y - pointMenu.offsetHeight / 2, box.clientHeight - pointMenu.offsetHeight), 0)}px`;
}

function addMenuNotice(text) {
    const noticeLine = byId('point-menu-notice');
    noticeLine.textContent = noticeLine.hidden ? text : `${noticeLine.textContent} ${text}`;
    noticeLine.hidden = false;
}

function hidePointMenu() {
    latestMenuRequestId++;
    pointMenu.hidden = true;
}

// '인원 평균 1,204명 · 밀집도 0.32'(시안). 값이 없으면 '데이터 없음'
function pointSummary(point) {
    const count = point ? valueOf(point, 'countAvg') : null;
    if (count === null) {
        return '데이터 없음';
    }
    const density = valueOf(point, 'density');
    const densityText = density === null ? '' : ` · 밀집도 ${formatDecimal(density, 2)}`;
    return `인원 평균 ${formatNumber(Math.round(count))}명${densityText}`;
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
    hidePointMenu();
    try {
        // 오류는 그래프 위 문구로 보이므로 api.js의 토스트는 끈다
        if (comparing) {
            const childrenQuery = {
                regionId: target.targetId,
                item: compareItemSelect.value,
                interval,
                from: query.from,
                to: query.to,
            };
            const response = await get(CHILDREN_API, childrenQuery, { toast: false });
            if (requestId === latestRequestId) {
                compareResult = response;
                render();
            }
        } else {
            const response = await get(SERIES_API, query, { toast: false });
            if (requestId === latestRequestId) {
                items = response.items;
                render();
            }
        }
    } catch (error) {
        if (requestId === latestRequestId) {
            items = [];
            compareResult = { series: [], omitted: 0 };
            render();
            showError(errorText(error));
        }
    }
}

function render() {
    if (comparing) {
        renderCompare();
        return;
    }
    omittedNotice.hidden = true;
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
    pointTimes = items.map((point) => point.at);
    chart.update(items.map((point) => axisLabel(point.at)), items.map((point) => tooltipTitle(point.at)), seriesByItem);
    // 오류 문구가 보이는 동안에는 '데이터 없음'을 같이 보이지 않는다
    emptyNotice.hidden = !errorNotice.hidden || items.some((point) => point.sampleSec > 0);
}

// 하위 단위마다 한 선. 하위 단위의 점은 모두 같은 구간이라 x축은 첫 단위의 점으로 만든다
function renderCompare() {
    const { series, omitted } = compareResult;
    const points = series.length > 0 ? series[0].points : [];
    pointTimes = points.map((point) => point.at);
    chart.compare(
        points.map((point) => axisLabel(point.at)),
        points.map((point) => tooltipTitle(point.at)),
        COMPARE_ITEM_KEYS[compareItemSelect.value],
        series.map((child) => ({
            name: child.name,
            values: child.points.map((point) => (point.value === null ? null : Number(point.value))),
        })),
    );
    // 10개를 넘으면 인원 평균 상위 10개만 온다(FNC-C-014 업무 규칙)
    omittedNotice.textContent = `나머지 ${omitted}개 생략`;
    omittedNotice.hidden = !(omitted > 0);
    const hasValue = series.some((child) => child.points.some((point) => point.value !== null));
    emptyNotice.hidden = !errorNotice.hidden || hasValue;
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
